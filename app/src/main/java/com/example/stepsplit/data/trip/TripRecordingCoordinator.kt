package com.example.stepsplit.data.trip

import android.util.Log
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.SpotFixRule
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Owns the actual "collect live GPS fixes and forward accepted ones to [TripRepository]" work,
 * deliberately kept as a plain class (not an Android [android.app.Service]) so it is fully
 * unit-testable with the tests' `FakeTripLocationClient` and a fake clock/repository, with no Robolectric or
 * real service lifecycle involved. [com.example.stepsplit.trip.service.TripRecordingService] is a
 * thin shell around this: it owns the foreground-service/notification mechanics, this owns the
 * actual recording logic, and it holds no reference to any Activity, ViewModel, or composable.
 *
 * [start] is idempotent: calling it again for a trip that is already being collected does not
 * create a second subscription to [locationClient] - see [isActive]. [stop] is idempotent too, and
 * is what removes the location subscription (via [locationClient]'s cold-flow cancellation) on
 * every terminal path - normal finish, error, or the service's own onDestroy as a safety net.
 *
 * It also keeps [latestFix], the newest fix it has received for the trip, for [TripSpotMarker]:
 * this is the one place that sees every fix, the ones the route then drops included.
 */
class TripRecordingCoordinator(
    private val repository: TripRepository,
    private val locationClient: TripLocationClient,
    private val scope: CoroutineScope,
) {
    /**
     * Atomic because a failing collector clears it from its own coroutine, concurrently with
     * [start]/[stop] on the caller's thread - see the compare-and-set in [start].
     */
    private val collectingJob = AtomicReference<Job?>(null)

    val isActive: Boolean get() = collectingJob.get()?.isActive == true

    /** The trip being collected for, or null when nothing is - set by [start], cleared by [stop]. */
    @Volatile
    var recordingTripId: Long? = null
        private set

    private val latestFixState = MutableStateFlow<RawLocationSample?>(null)

    /**
     * The newest fix (by capture time) received since [start] that [SpotFixRule.isUsable] accepts,
     * whether or not the route kept it; null before the first one and after [stop]. Updated before
     * the batch is handed to the repository, so a spot never waits on a Room write.
     */
    val latestFix: StateFlow<RawLocationSample?> = latestFixState.asStateFlow()

    /**
     * [onFailure] runs at most once if [locationClient]'s flow itself terminates with a
     * non-cancellation exception - e.g. the platform asynchronously rejecting location-update
     * registration (see [FusedTripLocationClient]'s doc comment), or a provider failure
     * mid-collection. `Flow.catch` is cancellation-transparent (it never sees a
     * [kotlinx.coroutines.CancellationException] raised by [stop] cancelling [collectingJob]), so a
     * normal stop never spuriously invokes this callback - only a genuine, unexpected failure does.
     * Collection is stopped before [onFailure] runs; the caller (see
     * [com.example.stepsplit.trip.service.TripRecordingService]) is responsible for marking the
     * affected trip interrupted and tearing down the foreground service/notification. An exception
     * thrown by [onFailure] itself is contained here: [scope] has no handler above it, so letting it
     * escape would crash the app.
     */
    fun start(tripId: Long, onFailure: suspend (Throwable) -> Unit = {}) {
        if (isActive) return
        latestFixState.value = null
        recordingTripId = tripId
        val job = locationClient.locationUpdates()
            .onEach { batch ->
                rememberLatestFix(batch)
                repository.recordAcceptedBatch(tripId, batch)
            }
            .catch { throwable ->
                // Clear only this collector's own reference. By the time a failure lands here, a
                // newer start() may already have replaced it; clearing that one would orphan a live
                // location subscription that stop() could then never cancel.
                collectingJob.compareAndSet(currentCoroutineContext()[Job], null)
                try {
                    onFailure(throwable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "recording failure handler failed: ${e.message}")
                }
            }
            .launchIn(scope)
        collectingJob.set(job)
    }

    fun stop() {
        collectingJob.getAndSet(null)?.cancel()
        recordingTripId = null
        latestFixState.value = null
    }

    private fun rememberLatestFix(batch: List<RawLocationSample>) {
        val newest = batch.filter(SpotFixRule::isUsable).maxByOrNull { it.capturedAtEpochSecond } ?: return
        latestFixState.update { current ->
            if (current == null || newest.capturedAtEpochSecond >= current.capturedAtEpochSecond) newest else current
        }
    }

    private companion object {
        const val TAG = "TripRecordingCoord"
    }
}
