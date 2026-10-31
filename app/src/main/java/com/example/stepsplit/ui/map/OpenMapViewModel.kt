package com.example.stepsplit.ui.map

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.route.ImportedRoute
import com.example.stepsplit.data.route.ImportedRouteRepository
import com.example.stepsplit.data.route.RouteFileRead
import com.example.stepsplit.data.route.RouteFileReader
import com.example.stepsplit.data.route.RouteImportOutcome
import com.example.stepsplit.data.route.RouteImportProblem
import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.data.trip.MapLocationClient
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripSpot
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.route.RouteBounds
import com.example.stepsplit.domain.route.RouteFollower
import com.example.stepsplit.domain.route.RouteLine
import com.example.stepsplit.domain.route.RouteSimplifier
import com.example.stepsplit.domain.route.RouteWaypoint
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import com.example.stepsplit.domain.trip.SmoothedRoute
import com.example.stepsplit.ui.trips.smoothedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OpenMapUiState(
    val isLoading: Boolean = true,
    /** The trip being recorded, as the map draws it; null when no trip records. */
    val recording: RecordingOnMap? = null,
    /** Every imported route, newest first, for the Routes list. */
    val importedRoutes: List<ImportedRoute> = emptyList(),
    /** The imported routes the map draws: the shown ones whose lines have been read. */
    val routesOnMap: List<ImportedRouteOnMap> = emptyList(),
)

/** An imported route as the map draws it: [lines] simplified for drawing (see [RouteSimplifier.forDrawing]). */
data class ImportedRouteOnMap(
    val id: Long,
    val name: String?,
    val lines: List<RouteLine>,
    val waypoints: List<RouteWaypoint>,
)

/** What the route strip under the map says: where the phone is along the closest shown route. */
data class RouteStrip(val routeId: Long, val routeName: String?, val progress: RouteFollower.Progress)

/** The import of a file, from "Open with", Share or the Routes list's Import. */
sealed interface RouteImportStatus {
    data object Importing : RouteImportStatus

    data class Finished(val outcome: RouteImportOutcome) : RouteImportStatus
}

/** The map is asked to show [bounds] (an imported route) once - [id] tells one ask from the next. */
data class RouteZoomRequest(val bounds: RouteBounds, val id: Long)

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
 *
 * Imported routes ([ImportedRouteRepository]): the Routes list, the shown ones on the map (read once
 * when shown, simplified for drawing), importing a file ([importRoute]), and the route strip
 * ([routeStrip]), worked out from the same fixes, only while the screen collects it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenMapViewModel(
    repository: TripRepository,
    private val locationClient: MapLocationClient,
    private val importedRouteRepository: ImportedRouteRepository,
    private val routeFileReader: RouteFileReader,
    /** The displayed route for a list of raw points - a parameter only so a test can replace it. */
    smoothRoute: (List<TripPoint>) -> SmoothedRoute = ::smoothedRoute,
) : ViewModel() {

    private val activeTripId: Flow<Long?> = repository.observeTrips()
        .map { trips -> trips.firstOrNull { it.state == TripState.ACTIVE }?.id }
        .distinctUntilChanged()

    private val recording: Flow<RecordingOnMap?> = activeTripId.flatMapLatest { tripId ->
        if (tripId == null) {
            flowOf(null)
        } else {
            combine(
                repository.observeTripPoints(tripId).map(smoothRoute).flowOn(Dispatchers.Default),
                repository.observeTripSpots(tripId),
            ) { route, spots -> RecordingOnMap(route = route.points, distanceMeters = route.distanceMeters, spots = spots) }
        }
    }

    private val importedRoutes: Flow<List<ImportedRoute>> = importedRouteRepository.observeRoutes()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** A shown route's lines and waypoints, read once and kept while it stays shown. */
    private class LoadedRoute(val drawnLines: List<RouteLine>, val waypoints: List<RouteWaypoint>, val follower: RouteFollower?)

    // Only touched from the mapLatest below, one run at a time.
    private val loadedRoutes = mutableMapOf<Long, LoadedRoute>()

    /** The shown routes with their lines: read from the database when one is first shown, then from [loadedRoutes]. */
    private val shownRoutes: Flow<List<Pair<ImportedRoute, LoadedRoute>>> = importedRoutes
        .map { routes -> routes.filter { it.shown } }
        .distinctUntilChanged()
        .mapLatest { shown ->
            loadedRoutes.keys.retainAll(shown.map { it.id }.toSet())
            shown.mapNotNull { route ->
                val loaded = loadedRoutes[route.id] ?: importedRouteRepository.geometry(route.id)?.let { geometry ->
                    LoadedRoute(
                        drawnLines = geometry.lines.map(RouteSimplifier::forDrawing),
                        waypoints = geometry.waypoints,
                        follower = geometry.lines.takeIf { it.isNotEmpty() }?.let(::RouteFollower),
                    ).also { loadedRoutes[route.id] = it }
                }
                loaded?.let { route to it }
            }
        }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val uiState: StateFlow<OpenMapUiState> = combine(recording, importedRoutes, shownRoutes) { recording, routes, shown ->
        OpenMapUiState(
            isLoading = false,
            recording = recording,
            importedRoutes = routes,
            routesOnMap = shown.map { (route, loaded) -> ImportedRouteOnMap(route.id, route.name, loaded.drawnLines, loaded.waypoints) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenMapUiState())

    private val locationAvailable = MutableStateFlow(false)

    private val _importStatus = MutableStateFlow<RouteImportStatus?>(null)

    /** The latest import, until the screen dismisses its message ([dismissImportStatus]). */
    val importStatus: StateFlow<RouteImportStatus?> = _importStatus.asStateFlow()

    private val _zoomRequest = MutableStateFlow<RouteZoomRequest?>(null)

    /** An imported route for the map to show, until the screen has ([onZoomRequestHandled]). */
    val zoomRequest: StateFlow<RouteZoomRequest?> = _zoomRequest.asStateFlow()
    private var zoomRequestCount = 0L

    /**
     * The newest fix: the phone's last known location first, then live ones. It keeps the last fix
     * once location stops (turned off, say), so the map can still show where the phone was - the
     * screen tells an old fix by its age ([remainingFreshMillis]).
     */
    val location: StateFlow<MapFix?> = locationAvailable
        .flatMapLatest { available -> if (available) liveFixes() else emptyFlow() }
        // No timeout: the request stops the moment the screen stops collecting.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * Where the phone is along the closest shown route, for each new fix; null with no fix or no
     * shown route with a line. Worked out only while the screen collects it - which, like
     * [location], is only while the Map tab is on screen - and never stored.
     */
    val routeStrip: StateFlow<RouteStrip?> = combine(location, shownRoutes) { fix, shown -> fix to shown }
        .mapLatest { (fix, shown) ->
            if (fix == null) return@mapLatest null
            // Off the main thread: a long route is tens of thousands of points to match against.
            withContext(Dispatchers.Default) {
                shown.mapNotNull { (route, loaded) ->
                    loaded.follower?.let { RouteStrip(route.id, route.name, it.progress(fix.latitude, fix.longitude, fix.accuracyMeters)) }
                }.minByOrNull { it.progress.offMeters }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Set by the screen: whether it may show where the phone is - location permission granted, and location turned on. */
    fun setLocationAvailable(available: Boolean) {
        locationAvailable.value = available
    }

    /**
     * Imports the GPX file at [uri] - opened with the app, shared to it, or picked with Import - and
     * shows it on the map: see [ImportedRouteRepository.import]. [importStatus] says how it went.
     */
    fun importRoute(uri: Uri) {
        viewModelScope.launch {
            _importStatus.value = RouteImportStatus.Importing
            val outcome = when (val read = routeFileReader.read(uri)) {
                is RouteFileRead.Read -> importedRouteRepository.import(read.file)
                RouteFileRead.TooBig -> RouteImportOutcome.Failed(RouteImportProblem.TOO_BIG)
                RouteFileRead.CantRead -> RouteImportOutcome.Failed(RouteImportProblem.CANT_READ)
            }
            _importStatus.value = RouteImportStatus.Finished(outcome)
            when (outcome) {
                is RouteImportOutcome.Imported -> requestZoom(outcome.route.bounds)
                is RouteImportOutcome.AlreadyImported -> requestZoom(outcome.route.bounds)
                is RouteImportOutcome.Failed -> {}
            }
        }
    }

    fun dismissImportStatus() {
        if (_importStatus.value is RouteImportStatus.Finished) _importStatus.value = null
    }

    /** Shows [route] on the map, and the whole of it. */
    fun zoomToRoute(route: ImportedRoute) {
        if (!route.shown) setRouteShown(route.id, true)
        requestZoom(route.bounds)
    }

    fun onZoomRequestHandled(id: Long) {
        if (_zoomRequest.value?.id == id) _zoomRequest.value = null
    }

    fun setRouteShown(id: Long, shown: Boolean) {
        viewModelScope.launch { importedRouteRepository.setShown(id, shown) }
    }

    fun renameRoute(id: Long, name: String) {
        viewModelScope.launch { importedRouteRepository.rename(id, name) }
    }

    fun deleteRoute(id: Long) {
        viewModelScope.launch { importedRouteRepository.delete(id) }
    }

    private fun requestZoom(bounds: RouteBounds) {
        _zoomRequest.value = RouteZoomRequest(bounds, ++zoomRequestCount)
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
