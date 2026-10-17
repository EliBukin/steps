package com.example.stepsplit.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.trip.SeaLevelHeights
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripSpot
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.model.TripType
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import com.example.stepsplit.domain.trip.TripElevation
import com.example.stepsplit.domain.trip.TripMotion
import com.example.stepsplit.domain.trip.TripTypeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TripDetailUiState(
    val isLoading: Boolean = true,
    val trip: TripSummary? = null,
    val points: List<TripPoint> = emptyList(),
    /** [points] split into speed-colored runs and gaps for the map - see [RouteSpeedColoring]. */
    val speedColoring: RouteSpeedColoring.SpeedColoredRoute? = null,
    /** [TripTypeRule.suggest] on [points] - the type the Edit trip dialog marks as suggested. */
    val suggestedType: TripType = TripType.WALK,
    /** Moving time and top speed of [points], counted as a trip of [type] - see [TripMotion]. Null when the route has fewer than two points. */
    val motion: TripMotion.Motion? = null,
    /** Climb, descent, highest and lowest point and the height chart of [points] - see [TripElevation]. Null when too few points have a height. */
    val elevation: TripElevation.Elevation? = null,
    /**
     * Whether [points]' heights, and so [elevation]'s, are above sea level (converted on Android 14 and
     * newer - see [SeaLevelHeights]). When false they are as stored, above the WGS84 ellipsoid: fine
     * for climb, descent and the chart's shape, but not as absolute heights, so the screen doesn't
     * show those.
     */
    val heightsAboveSeaLevel: Boolean = false,
    /** The spots marked during the trip, by number, with their heights converted like [points]'. */
    val spots: List<TripSpot> = emptyList(),
    /** Whether [spots]' heights are above sea level - see [heightsAboveSeaLevel]. */
    val spotHeightsAboveSeaLevel: Boolean = false,
    val deleted: Boolean = false,
    /** Whether deleting this trip keeps its time and distance in Stats - the delete dialog says which. */
    val keepDeletedTripsInStats: Boolean = false,
) {
    /** What the trip counts as: the owner's choice when they made one, otherwise [suggestedType]. */
    val type: TripType get() = trip?.typeOverride ?: suggestedType
}

/**
 * [uiState.points] and [uiState.trip]'s own `distanceMeters` are both derived from the *same*
 * `RouteSanitizer.sanitize().points -> RouteSmoother.smooth()` pipeline over the trip's raw stored
 * points - never the raw points list and never [TripSummary.distanceMeters] as persisted (which was
 * accumulated live, at accept-time, under whichever
 * [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy] was active when each point was
 * originally recorded - see that class's own doc comment for why an already-stored trip can still
 * carry outlier-inflated distance from before this policy was hardened). [RouteSanitizer] removes
 * points with concrete evidence against them; [RouteSmoother] then reduces the ordinary GPS wobble
 * that remains even among points the sanitizer had no reason to reject - see its own doc comment.
 * This two-stage pipeline is what guarantees the map polyline, camera bounds, start/finish markers,
 * GPX export, and the displayed distance in this screen can never disagree with each other: every
 * one of them reads from this single [uiState.points]/[uiState.trip] pair, computed once per
 * points-flow emission here rather than separately (or repeatedly, on every Compose recomposition)
 * downstream. The underlying stored rows themselves are never modified - this is purely a read-time,
 * display/export transform.
 *
 * The same goes for heights: on Android 14 and newer [seaLevelHeights] converts the stored heights
 * (above the WGS84 ellipsoid) to above sea level right here, so the elevation numbers, the height
 * chart and the GPX `<ele>` values all read the same converted [uiState.points]. The spots' heights
 * go through the same conversion, for the spot dialog and the GPX waypoints.
 *
 * Everything derived from the route is worked out off the main thread, once per points-flow
 * emission: renaming a trip or changing its type doesn't redo it. Only the moving time, which
 * depends on the type, is recounted then, from the already-smoothed points.
 */
class TripDetailViewModel(
    private val repository: TripRepository,
    private val tripId: Long,
    /** The "Keep deleted trips in Stats" setting - see [com.example.stepsplit.data.settings.AppSettings.keepDeletedTripsInStats]. */
    private val keepDeletedTripsInStats: Flow<Boolean>,
    /** Converts the stored heights to above sea level where the phone can - see [SeaLevelHeights]. */
    private val seaLevelHeights: SeaLevelHeights,
) : ViewModel() {

    /** Everything the screen shows that comes from the route alone. */
    private class DisplayedRoute(
        val distanceMeters: Double,
        val points: List<TripPoint>,
        val speedColoring: RouteSpeedColoring.SpeedColoredRoute,
        val suggestedType: TripType,
        val elevation: TripElevation.Elevation?,
        val heightsAboveSeaLevel: Boolean,
    )

    private val displayedRoute: Flow<DisplayedRoute> = repository.observeTripPoints(tripId).map { rawPoints ->
        val smoothed = RouteSmoother.smooth(RouteSanitizer.sanitize(rawPoints).points)
        val seaLevelPoints = seaLevelHeights.toSeaLevel(smoothed.points)
        val points = seaLevelPoints ?: smoothed.points
        DisplayedRoute(
            distanceMeters = smoothed.distanceMeters,
            points = points,
            speedColoring = RouteSpeedColoring.colorize(points),
            suggestedType = TripTypeRule.suggest(points),
            elevation = TripElevation.measure(points),
            heightsAboveSeaLevel = seaLevelPoints != null,
        )
    }.flowOn(Dispatchers.Default)

    /** The spots, with their heights above sea level where the phone can convert them, and whether it could. */
    private val displayedSpots: Flow<Pair<List<TripSpot>, Boolean>> = repository.observeTripSpots(tripId).map { spots ->
        if (spots.isEmpty()) return@map spots to false
        val converted = seaLevelHeights.toSeaLevel(spots.map { it.asPoint() })
        if (converted == null) {
            spots to false
        } else {
            spots.mapIndexed { index, spot -> spot.copy(altitudeMeters = converted[index].altitudeMeters) } to true
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<TripDetailUiState> = combine(
        repository.observeTrip(tripId),
        displayedRoute,
        displayedSpots,
        keepDeletedTripsInStats,
    ) { trip, route, (spots, spotHeightsAboveSeaLevel), keepInStats ->
        val type = trip?.typeOverride ?: route.suggestedType
        TripDetailUiState(
            isLoading = false,
            trip = trip?.copy(distanceMeters = route.distanceMeters),
            points = route.points,
            speedColoring = route.speedColoring,
            suggestedType = route.suggestedType,
            motion = if (route.points.size >= 2) TripMotion.measure(route.points, type) else null,
            elevation = route.elevation,
            heightsAboveSeaLevel = route.heightsAboveSeaLevel,
            spots = spots,
            spotHeightsAboveSeaLevel = spotHeightsAboveSeaLevel,
            deleted = trip == null,
            keepDeletedTripsInStats = keepInStats,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    /**
     * Saves the Edit trip dialog: [name] (blank clears it) and the [type] the owner picked. Picking
     * the suggested type stores no override, so the trip goes back to following the suggestion.
     */
    fun saveTripDetails(name: String, type: TripType) {
        val state = uiState.value
        if (state.trip == null) return
        val override = type.takeIf { it != state.suggestedType }
        viewModelScope.launch { repository.updateTripDetails(tripId, name, override) }
    }

    fun deleteTrip() {
        viewModelScope.launch { repository.deleteTrip(tripId, keepInStats = keepDeletedTripsInStats.first()) }
    }

    /** Names a spot; blank goes back to "Spot N". */
    fun renameSpot(spotId: Long, name: String) {
        viewModelScope.launch { repository.renameSpot(spotId, name) }
    }

    fun deleteSpot(spotId: Long) {
        viewModelScope.launch { repository.deleteSpot(spotId) }
    }
}

/** A spot as a point, only for [SeaLevelHeights], which converts points' heights. */
private fun TripSpot.asPoint() = TripPoint(
    capturedAtEpochSecond = capturedAtEpochSecond,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = 0f,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = null,
)
