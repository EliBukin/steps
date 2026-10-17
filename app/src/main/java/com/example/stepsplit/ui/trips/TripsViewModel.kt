package com.example.stepsplit.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.trip.SpotMarkResult
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.trip.SmoothedRoute
import com.example.stepsplit.trip.service.TripRecordingService
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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
 * plain repository calls with no service interaction at all, and [markSpot].
 *
 * Mark spot doesn't go through the service either: the recording runs in this same process, and
 * [com.example.stepsplit.data.trip.TripSpotMarker] does the work on its own process-lifetime scope,
 * so a spot still waiting for its fix is saved even if this screen goes away. Its outcome comes back
 * through [spotMarkResults] for the snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripsViewModel(
    private val repository: TripRepository,
    private val clock: Clock,
    /** Marks a spot on the trip being recorded - [com.example.stepsplit.data.trip.TripSpotMarker.markSpot] in the app. */
    private val markSpotOnTrip: suspend () -> SpotMarkResult,
    /** The displayed route for a list of raw points - a parameter only so a test can count how often it runs. */
    private val smoothRoute: (List<TripPoint>) -> SmoothedRoute = ::smoothedRoute,
) : ViewModel() {

    init {
        // Once per process start - see TripRepository.reconcileActiveTripOnLaunch's own doc
        // comment for what this does and its inherent timing caveat.
        viewModelScope.launch { repository.reconcileActiveTripOnLaunch(TripRecordingService.isRunning) }
    }

    private val activeTripId: Flow<Long?> = repository.observeTrips()
        .map { trips -> trips.firstOrNull { it.state == TripState.ACTIVE }?.id }
        .distinctUntilChanged()

    private val activeTripPoints: Flow<List<TripPoint>> =
        activeTripId.flatMapLatest { activeId -> if (activeId == null) flowOf(emptyList()) else repository.observeTripPoints(activeId) }

    private val activeTripSpotCount: Flow<Int> =
        activeTripId.flatMapLatest { activeId -> if (activeId == null) flowOf(0) else repository.observeTripSpots(activeId).map { it.size } }

    private val markingSpot = MutableStateFlow(false)

    private val spotResults = Channel<SpotMarkResult>(Channel.BUFFERED)

    /** The outcome of each [markSpot], once - the Trips screen shows it in a snackbar. */
    val spotMarkResults: Flow<SpotMarkResult> = spotResults.receiveAsFlow()

    /**
     * The active trip's displayed distance, from the same `RouteSanitizer -> RouteSmoother`
     * pipeline every other route display/export uses (see [TripDetailViewModel]'s own doc comment) -
     * not [TripSummary.distanceMeters] as persisted, which is accumulated live at accept-time and
     * can still include outlier-inflated segments an in-progress
     * [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy] pass didn't catch, or ordinary
     * wobble the live policy has no way to reduce (see [RouteSmoother]'s own doc comment). Once a
     * trip is active this recomputes as it grows: the smoother's endpoint safeguard keeps the most
     * recent point exact, while earlier points are progressively properly-centered as later points
     * arrive - no special-casing needed for "still recording" vs. "finished". Deliberately its own
     * [Flow], derived only from [activeTripPoints] - never from [ticker] - so reprocessing the whole
     * route only happens when the point list actually changes (a new fix arrives), not on every
     * one-second tick that exists purely to refresh [TripsUiState.elapsedSeconds].
     */
    private val activeTripSanitizedDistance: Flow<Double> =
        activeTripPoints.map { smoothRoute(it).distanceMeters }.flowOn(Dispatchers.Default)

    /** Finished trips with their displayed (cleaned-up and smoothed) distances and thumbnails - see [FinishedTripHistory]. */
    private val finishedTripHistory: Flow<List<FinishedTrip>> = FinishedTripHistory(repository, smoothRoute).finishedTrips

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.instant().epochSecond)
            delay(TICK_INTERVAL_MILLIS)
        }
    }

    private val recordingState: Flow<TripsUiState> = combine(
        repository.observeTrips(),
        activeTripPoints,
        activeTripSanitizedDistance,
        finishedTripHistory,
        ticker,
    ) { trips, points, sanitizedActiveDistance, history, nowEpochSecond ->
        val active = trips.firstOrNull { it.state == TripState.ACTIVE }?.copy(distanceMeters = sanitizedActiveDistance)
        val interrupted = trips.firstOrNull { it.state == TripState.INTERRUPTED }
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
    }

    val uiState: StateFlow<TripsUiState> = combine(recordingState, activeTripSpotCount, markingSpot) { state, spotCount, marking ->
        state.copy(spotCount = spotCount, isMarkingSpot = marking)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripsUiState())

    /**
     * The Mark spot button: one tap marks a spot, with no dialog. The button is disabled while a tap
     * still waits for its fix, so a second tap meanwhile does nothing. A tap after that which ends
     * on the same GPS fix as the trip's newest spot - a double tap, a bump in a jeep - saves nothing
     * new and says so ("Spot 3 is already marked here"); see
     * [com.example.stepsplit.data.trip.TripSpotMarker]. Once the phone has moved on, a tap is a new spot.
     */
    fun markSpot() {
        if (!markingSpot.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                spotResults.send(markSpotOnTrip())
            } finally {
                markingSpot.value = false
            }
        }
    }

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
