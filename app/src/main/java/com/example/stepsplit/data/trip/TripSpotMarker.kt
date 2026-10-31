package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.SpotFixRule
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What marking a spot did - the Trips screen's snackbar and the notification say it. */
sealed interface SpotMarkResult {
    /** Saved as spot [number] of the trip. */
    data class Marked(val number: Int) : SpotMarkResult

    /** The tap ended on the fix spot [number] already uses - a double tap, a bump - so nothing new was saved. */
    data class AlreadyMarked(val number: Int) : SpotMarkResult

    /** No fix fits [SpotFixRule] - nothing saved. */
    data object NoFix : SpotMarkResult

    /** No trip is recording (or it was interrupted, or finished before the tap, while waiting for a fix) - nothing saved. */
    data object NotRecording : SpotMarkResult

    /** The spot couldn't be saved. */
    data object Failed : SpotMarkResult
}

/**
 * Marks a spot on the trip being recorded: picks the fix by [SpotFixRule] and saves it with
 * [TripRepository.addSpot]. The Trips screen's Mark spot button and the recording notification's
 * Mark spot action both call [markSpot], so they behave alike.
 *
 * The fixes come from [coordinator] ([TripRecordingCoordinator.latestFix], which sees every fix the
 * recording receives) and, when the newest isn't close enough, also from one fresh request to
 * [locationClient]. Only a trip [coordinator] is collecting for, in this process, can get a spot:
 * "actively recording", not an ACTIVE row whose recording died.
 *
 * One fix, one spot (SpotFixRule step 5): marks run one at a time, and the fix the last one was
 * placed at is one more fix to choose from. In a jeep the first tap usually waits for a fresh fix
 * the recording never sees, so without it a bump a moment later would ask for another one, ten
 * metres on, and save a second spot; with it, that tap ends on the same fix, which
 * [TripRepository.addSpot] doesn't save again.
 *
 * A tap is on the trip that was recording when it was made: Finish landing while it waits for a
 * fix doesn't lose the spot, as long as the fix is from no later than the trip's end (see
 * [TripRepository.addSpot]).
 */
class TripSpotMarker(
    private val repository: TripRepository,
    private val coordinator: TripRecordingCoordinator,
    private val locationClient: TripLocationClient,
    private val clock: Clock,
    /** Process-lifetime - see [markSpot]. */
    private val scope: CoroutineScope,
    private val waitForFixMillis: Long = SpotFixRule.WAIT_FOR_FIX_MILLIS,
) {
    /** One mark at a time, so a tap sees the fix the one before it used. */
    private val marking = Mutex()

    /** The trip and fix the last spot was marked (or found already marked) at - guarded by [marking]. */
    private var lastMarked: Pair<Long, RawLocationSample>? = null

    /**
     * Marks a spot now and says how it went. The work runs on [scope], which outlives the caller:
     * leaving the Trips screen, or the service command for the notification action ending, while
     * this waits for a fix doesn't lose the spot - the caller only stops waiting for the result.
     */
    suspend fun markSpot(): SpotMarkResult = scope.async { mark() }.await()

    private suspend fun mark(): SpotMarkResult {
        val markedAtEpochSecond = clock.instant().epochSecond
        val tripId = coordinator.recordingTripId?.takeIf { coordinator.isActive } ?: return SpotMarkResult.NotRecording
        return try {
            marking.withLock {
                val trip = repository.getTrip(tripId)?.takeIf { it.takesSpotMarkedAt(markedAtEpochSecond) } ?: return SpotMarkResult.NotRecording
                val lastFix = lastMarked?.takeIf { it.first == tripId }?.second
                val fix = chooseFix(markedAtEpochSecond, trip.startEpochSecond, lastFix) ?: return SpotMarkResult.NoFix
                repository.addSpot(tripId, fix, markedAtEpochSecond).also { result ->
                    if (result is SpotMarkResult.Marked || result is SpotMarkResult.AlreadyMarked) lastMarked = tripId to fix
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotMarkResult.Failed
        }
    }

    /**
     * Steps 2, 3 and 5 of [SpotFixRule]: the newest fix if close enough, otherwise the closest that
     * arrives within [waitForFixMillis]. [lastFix], the fix the trip's last spot used, counts as one
     * of the fixes on hand, ahead of the recording's newest when both are as close in time.
     */
    private suspend fun chooseFix(markedAtEpochSecond: Long, tripStartEpochSecond: Long, lastFix: RawLocationSample?): RawLocationSample? {
        var best = listOfNotNull(lastFix, coordinator.latestFix.value)
            .fold(null as RawLocationSample?) { closest, fix -> SpotFixRule.closer(closest, fix, markedAtEpochSecond, tripStartEpochSecond) }
        if (!SpotFixRule.needsCloserFix(best, markedAtEpochSecond)) return best
        withTimeoutOrNull(waitForFixMillis) {
            merge(
                coordinator.latestFix.filterNotNull(),
                flow { locationClient.currentLocation()?.let { emit(it) } },
            ).first { fix ->
                best = SpotFixRule.closer(best, fix, markedAtEpochSecond, tripStartEpochSecond)
                !SpotFixRule.needsCloserFix(best, markedAtEpochSecond)
            }
        }
        return best
    }
}
