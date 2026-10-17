package com.example.stepsplit.data.trip

import com.example.stepsplit.domain.model.TripState
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
import kotlinx.coroutines.withTimeoutOrNull

/** What marking a spot did - the Trips screen's snackbar and the notification say it. */
sealed interface SpotMarkResult {
    /** Saved as spot [number] of the trip. */
    data class Marked(val number: Int) : SpotMarkResult

    /** No fix fits [SpotFixRule] - nothing saved. */
    data object NoFix : SpotMarkResult

    /** No trip is recording (or it finished while waiting for a fix) - nothing saved. */
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
            val trip = repository.getTrip(tripId)?.takeIf { it.state == TripState.ACTIVE.name } ?: return SpotMarkResult.NotRecording
            val fix = chooseFix(markedAtEpochSecond, trip.startEpochSecond) ?: return SpotMarkResult.NoFix
            repository.addSpot(tripId, fix)?.let { SpotMarkResult.Marked(it) } ?: SpotMarkResult.NotRecording
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotMarkResult.Failed
        }
    }

    /** Steps 2 and 3 of [SpotFixRule]: the newest fix if close enough, otherwise the closest that arrives within [waitForFixMillis]. */
    private suspend fun chooseFix(markedAtEpochSecond: Long, tripStartEpochSecond: Long): RawLocationSample? {
        var best = coordinator.latestFix.value?.let { SpotFixRule.closer(null, it, markedAtEpochSecond, tripStartEpochSecond) }
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
