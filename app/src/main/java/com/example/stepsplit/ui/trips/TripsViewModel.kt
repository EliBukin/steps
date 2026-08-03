package com.example.stepsplit.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.trip.service.TripRecordingService
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Trips tab's ViewModel. All recording state shown here (active/interrupted/history, distance,
 * accepted-point count) is derived from [TripRepository]'s Room-backed flows, never from this
 * ViewModel's or any Activity's own lifetime - per the product requirement, the UI must not assume
 * staying alive means recording is still active. [elapsedSeconds] is the one locally-computed
 * value, a simple per-second display tick layered on top of the durable `startEpochSecond` (the
 * same pattern [com.example.stepsplit.domain.time.currentDateFlow] uses for day-rollover) - it is
 * a display convenience, not a second source of truth for whether recording is active.
 *
 * Starting/finishing/resuming a trip is intentionally NOT done here: those are simple
 * Context-level `startForegroundService` calls the Trips screen composable makes directly (see
 * [com.example.stepsplit.trip.service.TripRecordingService] and
 * `TripRecordingCommandController`), sent synchronously from the button's own click handler so the
 * command reaches the service even if this ViewModel/its Activity is backgrounded immediately
 * afterward. The service is what actually creates/finishes/resumes the trip in Room, atomically and
 * with its own generation-safety guarantees; this ViewModel's flows pick that up automatically once
 * it does. This ViewModel only owns [finishInterruptedTripAtLastPoint] and [deleteTrip], which are
 * plain repository calls with no service interaction at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsViewModel(
    private val repository: TripRepository,
    private val clock: Clock,
) : ViewModel() {

    init {
        // Once per process start - see TripRepository.reconcileActiveTripOnLaunch's own doc
        // comment for what this does and its inherent timing caveat.
        viewModelScope.launch { repository.reconcileActiveTripOnLaunch(TripRecordingService.isRunning) }
    }

    private val activeTripPoints: Flow<List<TripPoint>> = repository.observeTrips()
        .map { trips -> trips.firstOrNull { it.state == TripState.ACTIVE }?.id }
        .distinctUntilChanged()
        .flatMapLatest { activeId -> if (activeId == null) flowOf(emptyList()) else repository.observeTripPoints(activeId) }

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.instant().epochSecond)
            delay(TICK_INTERVAL_MILLIS)
        }
    }

    val uiState: StateFlow<TripsUiState> = combine(
        repository.observeTrips(),
        activeTripPoints,
        ticker,
    ) { trips, points, nowEpochSecond ->
        val active = trips.firstOrNull { it.state == TripState.ACTIVE }
        val interrupted = trips.firstOrNull { it.state == TripState.INTERRUPTED }
        val history = trips.filter { it.state == TripState.FINISHED }
        val lastPoint = points.lastOrNull()
        TripsUiState(
            isLoading = false,
            activeTrip = active,
            interruptedTrip = interrupted,
            history = history,
            elapsedSeconds = active?.let { (nowEpochSecond - it.startEpochSecond).coerceAtLeast(0L) } ?: 0L,
            acceptedPointCount = points.size,
            gpsStatus = gpsStatusFor(lastPoint, nowEpochSecond),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripsUiState())

    fun finishInterruptedTripAtLastPoint(tripId: Long) {
        viewModelScope.launch { repository.finishInterruptedTripAtLastPoint(tripId) }
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { repository.deleteTrip(tripId) }
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
    }
}

private fun gpsStatusFor(lastPoint: TripPoint?, nowEpochSecond: Long): GpsStatus {
    if (lastPoint == null) return GpsStatus.SEARCHING
    if (nowEpochSecond - lastPoint.capturedAtEpochSecond > STALE_FIX_SECONDS) return GpsStatus.SEARCHING
    return if (lastPoint.accuracyMeters <= GOOD_ACCURACY_METERS) GpsStatus.GOOD else GpsStatus.WEAK
}

private const val STALE_FIX_SECONDS = 30L
private const val GOOD_ACCURACY_METERS = 15f
