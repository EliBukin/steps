package com.example.stepsplit.data.trip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Owns the actual "collect live GPS fixes and forward accepted ones to [TripRepository]" work,
 * deliberately kept as a plain class (not an Android [android.app.Service]) so it is fully
 * unit-testable with [FakeTripLocationClient] and a fake clock/repository, with no Robolectric or
 * real service lifecycle involved. [com.example.stepsplit.trip.service.TripRecordingService] is a
 * thin shell around this: it owns the foreground-service/notification mechanics, this owns the
 * actual recording logic, and it holds no reference to any Activity, ViewModel, or composable.
 *
 * [start] is idempotent: calling it again for a trip that is already being collected does not
 * create a second subscription to [locationClient] - see [isActive]. [stop] is idempotent too, and
 * is what removes the location subscription (via [locationClient]'s cold-flow cancellation) on
 * every terminal path - normal finish, error, or the service's own onDestroy as a safety net.
 */
class TripRecordingCoordinator(
    private val repository: TripRepository,
    private val locationClient: TripLocationClient,
    private val scope: CoroutineScope,
) {
    private var collectingJob: Job? = null

    val isActive: Boolean get() = collectingJob?.isActive == true

    fun start(tripId: Long) {
        if (isActive) return
        collectingJob = locationClient.locationUpdates()
            .onEach { batch -> repository.recordAcceptedBatch(tripId, batch) }
            .launchIn(scope)
    }

    fun stop() {
        collectingJob?.cancel()
        collectingJob = null
    }
}
