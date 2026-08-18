package com.example.stepsplit.ui.trips

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.ln
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/**
 * Pure camera-fit math for the completed-trip map ([TripRouteMapCard]): how the camera should frame
 * the trip's already-recorded points alone (never a new location fix). [BoundingBox] and
 * [Position] are plain immutable coordinate value types with no Android/MapLibre-runtime
 * dependency, so this stays directly plain-JVM unit-testable.
 */
internal object RouteCameraBounds {
    /**
     * Minimum span applied around the point extent, in degrees (roughly 150m of latitude) - keeps
     * a single point, or a cluster of identical/near-identical points, at a sensible, readable
     * zoom instead of an undefined or extreme one.
     */
    private const val MIN_SPAN_DEGREES = 0.0015

    /** Extra fractional padding outside the raw point extent, so the route never touches the map's edge. */
    private const val PADDING_FRACTION = 0.15

    /** A route spanning the full globe in longitude renders at this floor - avoids `log2(0)`. */
    private const val MIN_ZOOM = 0.0

    /** MapLibre/OpenFreeMap vector tiles stop providing useful extra detail well before this. */
    private const val MAX_ZOOM = 16.0

    /**
     * The camera fit for a route: either a [Bounded] box MapLibre can frame directly, or - for a
     * route that crosses the antimeridian, see [compute] - a [Centered] position computed without
     * relying on a [BoundingBox] at all, since [BoundingBox] cannot safely express a wrapped
     * longitude interval on this stack (see [compute]'s doc for why).
     */
    sealed interface Fit {
        data class Bounded(val boundingBox: BoundingBox) : Fit
        data class Centered(val target: Position, val zoom: Double) : Fit
    }

    /**
     * Returns null for an empty route - there is nothing to frame. Otherwise, detects whether the
     * route crosses the antimeridian (e.g. a point at 179.9°E and another at 179.9°W, ~0.2° apart
     * the short way around but ~359.8° apart by naive min/max) and picks the appropriate [Fit]:
     *
     * - **Not crossing**: [Fit.Bounded], the original min/max-longitude bounding box, padded.
     * - **Crossing**: [Fit.Centered]. MapLibre's Android stack has no way to express a bounding box
     *   that wraps around ±180° - confirmed directly from this project's actual dependencies
     *   (`org.maplibre.spatialk:geojson-jvm:0.7.0`'s `BoundingBox` is a plain four-double holder
     *   with no wrap-aware validation or normalization, and `org.maplibre.gl:android-sdk:13.0.2`'s
     *   `LatLngBounds` - what a `BoundingBox` is converted to on the way to
     *   `CameraUpdateFactory.newLatLngBounds`, see `AndroidMapAdapter.setCameraPosition` and
     *   `UtilKt.toLatLngBounds` in `maplibre-compose-android:0.14.0` - likewise takes `west`/`east`
     *   as plain doubles with no dateline-aware `contains`/fit logic). A `west > east` box on this
     *   stack is therefore silently misinterpreted as spanning nearly the entire globe rather than
     *   the narrow wrapped strip it should represent - exactly the bug this fixes. Instead, this
     *   computes the route's center and a conservative camera zoom directly (bypassing `BoundingBox`
     *   entirely), applied via `CameraState.position =` rather than `jumpTo(boundingBox, ...)`;
     *   see [TripRouteMapCard]'s `LiveMap`.
     */
    fun compute(points: List<TripPoint>): Fit? {
        if (points.isEmpty()) return null

        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val rawMinLon = points.minOf { it.longitude }
        val rawMaxLon = points.maxOf { it.longitude }
        val rawLonSpan = rawMaxLon - rawMinLon

        // Longitudes re-expressed in a continuous [0, 360) space (shifting every negative value up
        // by 360°) so a route that actually crosses ±180° gets a *smaller* span this way than the
        // naive min/max - e.g. {179.9, -179.9} spans 359.8° raw, but only 0.2° shifted.
        val shiftedLons = points.map { if (it.longitude < 0.0) it.longitude + 360.0 else it.longitude }
        val shiftedMinLon = shiftedLons.min()
        val shiftedMaxLon = shiftedLons.max()
        val shiftedLonSpan = shiftedMaxLon - shiftedMinLon

        val crossesAntimeridian = shiftedLonSpan < rawLonSpan

        val centerLat = (minLat + maxLat) / 2.0
        val latSpan = (maxLat - minLat).coerceAtLeast(MIN_SPAN_DEGREES)

        if (!crossesAntimeridian) {
            val centerLon = (rawMinLon + rawMaxLon) / 2.0
            val halfLat = latSpan / 2.0 * (1.0 + PADDING_FRACTION)
            val halfLon = rawLonSpan.coerceAtLeast(MIN_SPAN_DEGREES) / 2.0 * (1.0 + PADDING_FRACTION)
            return Fit.Bounded(
                BoundingBox(
                    west = (centerLon - halfLon).coerceIn(-180.0, 180.0),
                    south = (centerLat - halfLat).coerceIn(-90.0, 90.0),
                    east = (centerLon + halfLon).coerceIn(-180.0, 180.0),
                    north = (centerLat + halfLat).coerceIn(-90.0, 90.0),
                ),
            )
        }

        // Crossing: center in the shifted space (never near-antipodal by construction, since we
        // only get here when shifting made the span narrower), then wrap back into [-180, 180].
        val shiftedCenterLon = (shiftedMinLon + shiftedMaxLon) / 2.0
        val centerLon = if (shiftedCenterLon > 180.0) shiftedCenterLon - 360.0 else shiftedCenterLon
        val paddedLonSpan = shiftedLonSpan.coerceAtLeast(MIN_SPAN_DEGREES) * (1.0 + PADDING_FRACTION)
        val paddedLatSpan = latSpan * (1.0 + PADDING_FRACTION)
        return Fit.Centered(
            target = Position(longitude = centerLon, latitude = centerLat),
            zoom = zoomForSpan(lonSpanDegrees = paddedLonSpan, latSpanDegrees = paddedLatSpan),
        )
    }

    /**
     * A conservative zoom estimate for a span of [lonSpanDegrees] by [latSpanDegrees], using the
     * standard Web Mercator relationship between zoom level and visible longitude span (each zoom
     * level halves the visible span of a 360°-wide world) as an approximation - it does not account
     * for the actual on-screen viewport size (unknown to this pure function) or for latitude's
     * Mercator distortion, so it is intentionally conservative rather than pixel-exact. Good enough
     * to keep a dateline-crossing route roughly framed without the wrapping-`BoundingBox` bug this
     * exists to avoid; ordinary (non-crossing) routes still use the precise [Fit.Bounded] path above.
     */
    private fun zoomForSpan(lonSpanDegrees: Double, latSpanDegrees: Double): Double {
        val span = maxOf(lonSpanDegrees, latSpanDegrees, MIN_SPAN_DEGREES)
        val zoom = ln(360.0 / span) / ln(2.0)
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
