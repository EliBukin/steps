package com.example.stepsplit.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.data.trip.MapLocationClient
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripSpot
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import com.example.stepsplit.domain.trip.SmoothedRoute
import com.example.stepsplit.ui.trips.smoothedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class OpenMapUiState(
    val isLoading: Boolean = true,
    /** The trip being recorded, as the map draws it; null when no trip records. */
    val recording: RecordingOnMap? = null,
)

data class RecordingOnMap(
    /**
     * Its route as every other screen shows it (cleaned up and smoothed - see [smoothedRoute]).
     * Empty until the first point is kept.
     */
    val route: List<TripPoint>,
    /** The distance of [route], as the Trips tab's recording card shows it. */
    val distanceMeters: Double,
    val spots: List<TripSpot>,
) {
    /** [route] split where nothing was recorded for a while, as the trip screen splits it - see [splitAtGaps]. */
    val parts: RouteParts = splitAtGaps(route)
}

/** A route as the map draws it: the [stretches] that were recorded, and the [gaps] between them, drawn gray and dashed. */
data class RouteParts(
    val stretches: List<List<TripPoint>>,
    val gaps: List<Pair<TripPoint, TripPoint>>,
)

/**
 * Splits [route] at each gap - a stretch where more than a minute passed and the phone moved more
 * than 100 m, so nothing shows how it got there (the recording was interrupted and resumed, or the
 * GPS lost its fix) - by the trip screen's own rule, [RouteSpeedColoring.isGap].
 */
internal fun splitAtGaps(route: List<TripPoint>): RouteParts {
    if (route.isEmpty()) return RouteParts(stretches = emptyList(), gaps = emptyList())
    val stretches = mutableListOf<List<TripPoint>>()
    val gaps = mutableListOf<Pair<TripPoint, TripPoint>>()
    var current = mutableListOf(route.first())
    for (i in 1 until route.size) {
        val from = route[i - 1]
        val to = route[i]
        if (RouteSpeedColoring.isGap(from, to)) {
            stretches += current
            gaps += from to to
            current = mutableListOf(to)
        } else {
            current += to
        }
    }
    stretches += current
    return RouteParts(stretches = stretches, gaps = gaps)
}

/**
 * The Map tab: where the phone is, and the trip being recorded, if any.
 *
 * [location] asks [MapLocationClient] for fixes only while two things hold: the screen collects it
 * (it does so only while it's on screen with the app in the foreground - leaving the map, or the
 * app, stops the location request at once), and the screen has said location is available
 * ([setLocationAvailable]: permission granted and location turned on). The fixes are only shown,
 * never stored; the trip being recorded keeps getting its points from its own recording, as before.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenMapViewModel(
    repository: TripRepository,
    private val locationClient: MapLocationClient,
    /** The displayed route for a list of raw points - a parameter only so a test can replace it. */
    smoothRoute: (List<TripPoint>) -> SmoothedRoute = ::smoothedRoute,
) : ViewModel() {

    private val activeTripId: Flow<Long?> = repository.observeTrips()
        .map { trips -> trips.firstOrNull { it.state == TripState.ACTIVE }?.id }
        .distinctUntilChanged()

    val uiState: StateFlow<OpenMapUiState> = activeTripId.flatMapLatest { tripId ->
        if (tripId == null) {
            flowOf(OpenMapUiState(isLoading = false))
        } else {
            combine(
                repository.observeTripPoints(tripId).map(smoothRoute).flowOn(Dispatchers.Default),
                repository.observeTripSpots(tripId),
            ) { route, spots ->
                OpenMapUiState(
                    isLoading = false,
                    recording = RecordingOnMap(route = route.points, distanceMeters = route.distanceMeters, spots = spots),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenMapUiState())

    private val locationAvailable = MutableStateFlow(false)

    /**
     * The newest fix: the phone's last known location first, then live ones. It keeps the last fix
     * once location stops (turned off, say), so the map can still show where the phone was - the
     * screen tells an old fix by its age ([remainingFreshMillis]).
     */
    val location: StateFlow<MapFix?> = locationAvailable
        .flatMapLatest { available -> if (available) liveFixes() else emptyFlow() }
        // No timeout: the request stops the moment the screen stops collecting.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Set by the screen: whether it may show where the phone is - location permission granted, and location turned on. */
    fun setLocationAvailable(available: Boolean) {
        locationAvailable.value = available
    }

    private fun liveFixes(): Flow<MapFix> = flow {
        locationClient.lastLocation()?.let { emit(it) }
        emitAll(locationClient.locationUpdates())
    }.catch {
        // The request was refused (permission withdrawn in between, say): no new fixes. The screen
        // re-checks the permission when it comes back to the front, which starts a fresh request.
    }
}

/**
 * How much longer [fix] counts as where the phone is now, in milliseconds; 0 once it's older than
 * [FRESH_FIX_MILLIS], or when there is none. An old fix still shows, grayed, with "Finding your
 * location...".
 */
internal fun remainingFreshMillis(fix: MapFix?, nowElapsedRealtimeNanos: Long): Long {
    if (fix == null) return 0L
    val ageMillis = (nowElapsedRealtimeNanos - fix.elapsedRealtimeNanos) / 1_000_000L
    return (FRESH_FIX_MILLIS - ageMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
}

/** A fix this old or newer is where the phone is now; older, and the GPS may have lost its signal. */
internal const val FRESH_FIX_MILLIS = 30_000L
