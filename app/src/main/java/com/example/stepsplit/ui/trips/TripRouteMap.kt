package com.example.stepsplit.ui.trips

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripSpot
import com.example.stepsplit.domain.trip.RoutePoint
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import com.example.stepsplit.ui.map.OnMapColors
import com.example.stepsplit.ui.map.rememberMapBackground
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

private const val ROUTE_LAYER_ID = "trip-route-line"
private const val ROUTE_CASING_LAYER_ID = "trip-route-casing"
private const val GAP_LAYER_ID = "trip-route-gaps"

/** The feature property holding each speed-colored run's color, as "#RRGGBB". */
private const val COLOR_PROPERTY = "color"

private const val START_LAYER_ID = "trip-route-start"
private const val FINISH_LAYER_ID = "trip-route-finish"

/**
 * The completed-trip route on a real geographic basemap, in a rounded Material 3 card that fills
 * whatever size [modifier] gives it: fits the camera to the full route with padding, draws the
 * route as a polyline colored by speed ([speedColoring], with a small legend on the map; gaps gray
 * and dashed), marks the start and finish distinctly, and draws the
 * spots marked during the trip as numbered markers that [onSpotClick] on a tap. A round "Show whole route" button
 * on the map re-frames the route, north up, after the user pans, zooms or rotates away. Must not be placed inside a vertically scrollable container - see `TripDetailScreen` for why that breaks the map's gestures. Reads only the already-recorded [points] (must be non-empty - the caller
 * shows its own empty state instead, as before) - never requests a new location fix, never shows
 * the user's live position, and never associates the route with step data.
 *
 * Falls back to the offline, non-geographic [RouteTraceCanvas] trace - with an honest "map
 * unavailable" message and a manual "Retry map" action - if the map fails to load (rare now that
 * its style is inside the app; no network alone doesn't do it - see below). Retry only ever runs when the user taps it (never automatically/on a loop): toggling
 * [mapLoadFailed] back to `false` here leaves the live-map branch below and re-enters it, which -
 * being a plain Compose conditional, not a `key()`-stabilized call - disposes the previous (failed)
 * [LiveMap] instance and its underlying native map entirely and composes a brand-new one, so the
 * real style is attempted fresh rather than reusing whatever broke before. The failure is also
 * reset (without user action) whenever [points] actually changes, so a stale failure from a
 * different trip never blocks an otherwise-healthy load.
 *
 * The map is the app's light outdoor style (see [rememberMapBackground]) in dark mode too, and what's
 * drawn on it keeps its light-map colors ([OnMapColors]); the card, the buttons and the legend
 * still follow the app's theme. Without internet the style still loads - it's inside the app - and
 * shows the map tiles viewed before, from MapLibre's cache, on its plain land color.
 */
@Composable
fun TripRouteMapCard(
    points: List<TripPoint>,
    speedColoring: RouteSpeedColoring.SpeedColoredRoute?,
    modifier: Modifier = Modifier,
    /** The trip's marked spots, drawn as numbered markers - see [SpotFillColor]. */
    spots: List<TripSpot> = emptyList(),
    /** A spot marker was tapped, with that spot's id. */
    onSpotClick: (Long) -> Unit = {},
    /**
     * How much of the card's bottom is covered by something on top of it (the trip screen's details
     * panel). The map still draws there, but its buttons, logo and attribution move above it, and the
     * route is framed in the part that stays visible.
     */
    bottomInset: Dp = 0.dp,
) {
    require(points.isNotEmpty()) { "TripRouteMapCard requires at least one point; callers show their own empty state." }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        var mapLoadFailed by remember { mutableStateOf(false) }

        LaunchedEffect(points) { mapLoadFailed = false }

        if (mapLoadFailed) {
            OfflineFallback(points = points, bottomInset = bottomInset, onRetry = { mapLoadFailed = false })
        } else {
            LiveMap(
                points = points,
                speedColoring = speedColoring,
                spots = spots,
                onSpotClick = onSpotClick,
                bottomInset = bottomInset,
                onLoadFailed = { mapLoadFailed = true },
            )
        }
    }
}

@Composable
private fun LiveMap(
    points: List<TripPoint>,
    speedColoring: RouteSpeedColoring.SpeedColoredRoute?,
    spots: List<TripSpot>,
    onSpotClick: (Long) -> Unit,
    bottomInset: Dp,
    onLoadFailed: () -> Unit,
) {
    val first = points.first()
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = Position(longitude = first.longitude, latitude = first.latitude), zoom = 14.0),
    )
    // From an offline map pack where the route is inside one (see rememberMapBackground).
    val baseStyle = rememberMapBackground(cameraState).style
    val fit = remember(points) { RouteCameraBounds.compute(points) }
    val mapDescription = stringResource(R.string.cd_trip_map_with_markers) +
        if (spots.isNotEmpty()) " " + stringResource(R.string.cd_trip_map_spots, spots.size) else ""
    val coroutineScope = rememberCoroutineScope()

    // Frames the route on first display. When the panel over the map opens or closes, the route is
    // framed again in the part left visible - once the panel stops moving, and only if the user hasn't
    // panned, zoomed or rotated the map since the last framing: then it stays as they left it.
    var movedByUser by remember { mutableStateOf(false) }
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.moveReason }.collect { if (it == CameraMoveReason.GESTURE) movedByUser = true }
    }
    var framedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(fit, bottomInset) {
        if (framedOnce) {
            delay(INSET_SETTLE_MILLIS)
            if (movedByUser) return@LaunchedEffect
        }
        cameraState.showWholeRoute(fit, animate = framedOnce, bottomInset = bottomInset)
        framedOnce = true
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
            baseStyle = baseStyle,
            cameraState = cameraState,
            options = MapOptions(
                // Pan, zoom, double-tap and two-finger rotate stay on; MapLibre's compass appears once
                // the map is rotated, and tapping it (or "Show whole route") turns it back to north.
                // Only tilt is locked: in a map this small it mostly fires by accident while zooming.
                gestureOptions = GestureOptions(isTiltEnabled = false),
                // MapLibre's own scale bar shows feet on a US-region phone; MetricScaleBar below
                // replaces it. Logo, attribution and compass stay on, above whatever covers the bottom.
                ornamentOptions = OrnamentOptions(padding = PaddingValues(bottom = bottomInset), isScaleBarEnabled = false),
            ),
            onMapLoadFailed = { onLoadFailed() },
        ) {
            if (speedColoring != null && speedColoring.runs.isNotEmpty()) {
                val runsSource = rememberGeoJsonSource(data = remember(speedColoring) { speedColoring.runsGeoJson() })
                LineLayer(
                    id = ROUTE_CASING_LAYER_ID,
                    source = runsSource,
                    color = const(OnMapColors.RouteCasing),
                    width = const(6.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
                LineLayer(
                    id = ROUTE_LAYER_ID,
                    source = runsSource,
                    color = feature.get(COLOR_PROPERTY).convertToColor(),
                    width = const(4.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
            } else if (points.size >= 2 && speedColoring == null) {
                val routeSource = rememberGeoJsonSource(
                    data = GeoJsonData.Features(LineString(points.map { Position(longitude = it.longitude, latitude = it.latitude) })),
                )
                LineLayer(
                    id = ROUTE_LAYER_ID,
                    source = routeSource,
                    color = const(OnMapColors.PlainRoute),
                    width = const(4.dp),
                )
            }
            if (speedColoring != null && speedColoring.gaps.isNotEmpty()) {
                val gapsSource = rememberGeoJsonSource(data = remember(speedColoring) { speedColoring.gapsGeoJson() })
                LineLayer(
                    id = GAP_LAYER_ID,
                    source = gapsSource,
                    color = const(OnMapColors.Gap),
                    width = const(3.dp),
                    dasharray = const(listOf<Number>(2, 2)),
                )
            }

            val last = points.last()
            val startSource = rememberGeoJsonSource(
                data = GeoJsonData.Features(Point(longitude = first.longitude, latitude = first.latitude)),
            )
            CircleLayer(id = START_LAYER_ID, source = startSource, color = const(OnMapColors.Start), radius = const(7.dp))

            val finishSource = rememberGeoJsonSource(
                data = GeoJsonData.Features(Point(longitude = last.longitude, latitude = last.latitude)),
            )
            CircleLayer(id = FINISH_LAYER_ID, source = finishSource, color = const(OnMapColors.Finish), radius = const(7.dp))

            // Spots last, so they sit on top of the route and the start and finish dots.
            if (spots.isNotEmpty()) SpotMarkerLayers(spots, onSpotClick = onSpotClick)
        }

        MetricScaleBar(
            metersPerDp = cameraState.metersPerDpAtTarget,
            // Same maximum width (half the map), corner and margin as MapLibre's own scale bar; the
            // compass takes the opposite corner.
            maxWidth = maxWidth * 0.5f,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )

        // Under the scale bar: both describe how to read the map.
        speedColoring?.scale?.let { scale ->
            SpeedLegend(scale = scale, modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 36.dp))
        }

        // Bottom end, just above MapLibre's attribution button, which sits in that corner (both
        // mirror in right-to-left languages); the compass has the top end and the scale bar the top start.
        SmallFloatingActionButton(
            onClick = {
                movedByUser = false
                coroutineScope.launch { cameraState.showWholeRoute(fit, animate = true, bottomInset = bottomInset) }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp + bottomInset),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fit_route_24),
                contentDescription = stringResource(R.string.trip_detail_map_show_route_action),
            )
        }
    }
}

private fun RouteSpeedColoring.SpeedColoredRoute.runsGeoJson() = GeoJsonData.Features(
    FeatureCollection(
        runs.map { run ->
            Feature(
                LineString(run.points.map { Position(longitude = it.longitude, latitude = it.latitude) }),
                buildJsonObject { put(COLOR_PROPERTY, String.format(Locale.ROOT, "#%06X", run.color and 0xFFFFFF)) },
            )
        },
    ),
)

private fun RouteSpeedColoring.SpeedColoredRoute.gapsGeoJson() = GeoJsonData.Features(
    FeatureCollection(
        gaps.map { gap ->
            Feature(
                LineString(
                    Position(longitude = gap.from.longitude, latitude = gap.from.latitude),
                    Position(longitude = gap.to.longitude, latitude = gap.to.latitude),
                ),
                null,
            )
        },
    ),
)

/**
 * Frames the whole route, north up - on first display, and again whenever the user taps the "Show
 * whole route" button after panning, zooming or rotating away.
 */
private suspend fun CameraState.showWholeRoute(fit: RouteCameraBounds.Fit?, animate: Boolean, bottomInset: Dp) {
    val padding = PaddingValues(start = ROUTE_PADDING, top = ROUTE_PADDING, end = ROUTE_PADDING, bottom = ROUTE_PADDING + bottomInset)
    when (fit) {
        is RouteCameraBounds.Fit.Bounded ->
            if (animate) {
                animateTo(boundingBox = fit.boundingBox, padding = padding)
            } else {
                jumpTo(boundingBox = fit.boundingBox, padding = padding)
            }
        is RouteCameraBounds.Fit.Centered -> {
            val target = CameraPosition(target = fit.target, zoom = fit.zoom)
            if (animate) animateTo(target) else position = target
        }
        null -> {}
    }
}

private val ROUTE_PADDING = 28.dp

/** How long the bottom inset must stay put before the route is framed again: the panel's open or close animation. */
private const val INSET_SETTLE_MILLIS = 350L

@Composable
private fun ColumnScope.OfflineFallback(points: List<TripPoint>, bottomInset: Dp, onRetry: () -> Unit) {
    // Weighted without filling, so the message and Retry below always stay visible however little
    // height the card is given. RouteTraceCanvas keeps its own square aspect ratio within that.
    RouteTraceCanvas(
        points = points.map { RoutePoint(latitude = it.latitude, longitude = it.longitude) },
        modifier = Modifier.weight(1f, fill = false),
    )
    Text(
        text = stringResource(R.string.trip_detail_map_unavailable_message),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
    TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.trip_detail_map_retry_action))
    }
    Spacer(Modifier.height(bottomInset))
}
