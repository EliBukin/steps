package com.example.stepsplit.ui.map

import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.ui.trips.RouteCameraBounds
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

/** Pure camera rules for the Open map screen, kept apart from the screen so the JVM tests can check them. */
internal object OpenMapCamera {
    /** Following the dot: paths and streets readable, a few hundred meters around. */
    const val FOLLOW_ZOOM = 16.0

    /** Following from a map zoomed out further than this zooms in (see [followZoom]); closer in, the zoom stays as the user left it. */
    const val MIN_FOLLOW_ZOOM = 13.0

    /**
     * The accuracy circle is kept within this radius on screen: with only approximate location
     * allowed (a circle of a few kilometers), the map zooms out until it fits, instead of the circle
     * covering the whole map.
     */
    const val MAX_ACCURACY_RADIUS_DP = 120.0

    /** No fix yet, but a trip records: its newest point, a little wider than [FOLLOW_ZOOM]. */
    private const val RECORDING_ZOOM = 15.0

    /** Nothing to show yet (no fix, no permission, no trip): the whole world, until the first fix. */
    private const val WORLD_ZOOM = 1.0
    private val WORLD_CENTER = Position(longitude = 0.0, latitude = 20.0)

    /** MapLibre's world at zoom 0: one 512 dp tile around the equator. */
    private const val EARTH_CIRCUMFERENCE_METERS = 40_075_016.686
    private const val TILE_SIZE_DP = 512.0

    /** Where the map opens: on the phone's fix, else on the trip being recorded, else on the whole world. */
    fun initialPosition(fix: MapFix?, route: List<TripPoint>): CameraPosition {
        val last = route.lastOrNull()
        return when {
            fix != null -> CameraPosition(target = Position(longitude = fix.longitude, latitude = fix.latitude), zoom = fittingZoom(fix))
            last != null -> CameraPosition(target = Position(longitude = last.longitude, latitude = last.latitude), zoom = RECORDING_ZOOM)
            else -> CameraPosition(target = WORLD_CENTER, zoom = WORLD_ZOOM)
        }
    }

    /**
     * The zoom to follow [fix] at, from the map's [currentZoom]: the user's own zoom, unless the map
     * is zoomed out far ([isFarOut]) - then [fittingZoom].
     */
    fun followZoom(currentZoom: Double, fix: MapFix): Double = if (isFarOut(currentZoom, fix)) fittingZoom(fix) else currentZoom

    /** Zoomed out further than following keeps: the map then moves to the fix at once rather than gliding there across the world. */
    fun isFarOut(currentZoom: Double, fix: MapFix): Boolean = currentZoom < min(MIN_FOLLOW_ZOOM, fittingZoom(fix))

    /**
     * [FOLLOW_ZOOM], or less where the fix's accuracy circle would be wider than
     * [MAX_ACCURACY_RADIUS_DP] at it.
     */
    fun fittingZoom(fix: MapFix): Double {
        val accuracy = fix.accuracyMeters?.takeIf { it > 0f } ?: return FOLLOW_ZOOM
        val metersPerDpAtZoomZero = EARTH_CIRCUMFERENCE_METERS * cos(Math.toRadians(fix.latitude)) / TILE_SIZE_DP
        return log2(metersPerDpAtZoomZero * MAX_ACCURACY_RADIUS_DP / accuracy).coerceIn(WORLD_ZOOM, FOLLOW_ZOOM)
    }

    /** "Show whole route": the route recorded so far, and where the phone is now. */
    fun wholeRouteFit(route: List<TripPoint>, fix: MapFix?): RouteCameraBounds.Fit? {
        val here = fix?.let { TripPoint(capturedAtEpochSecond = 0L, latitude = it.latitude, longitude = it.longitude, accuracyMeters = 0f, altitudeMeters = null, speedMetersPerSecond = null) }
        return RouteCameraBounds.compute(if (here == null) route else route + here)
    }
}
