package com.example.stepsplit.ui.trips

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripSpot
import com.example.stepsplit.domain.trip.RoutePoint
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * OpenFreeMap (https://openfreemap.org) - a free, keyless vector tile provider with no request
 * limits and no account/API key, explicitly permitting commercial and hobby app use. Style choice
 * follows the system light/dark setting; both styles carry their own OSM/OpenMapTiles attribution,
 * shown automatically by MapLibre's built-in attribution control (see [MaplibreMap]'s default
 * `MapOptions.ornamentOptions`, never disabled here). Only ever requested while this composable is
 * part of the composition, i.e. only while the trip-detail screen showing it is visible - no
 * prefetching, no offline pack download, no background loading.
 */
private const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** Matches [RouteTraceCanvas]'s fixed "start" marker color, independent of theme accent. */
private val StartMarkerColor = Color(0xFF2E7D32)

private const val ROUTE_LAYER_ID = "trip-route-line"
private const val ROUTE_CASING_LAYER_ID = "trip-route-casing"
private const val GAP_LAYER_ID = "trip-route-gaps"

/** The feature property holding each speed-colored run's color, as "#RRGGBB". */
private const val COLOR_PROPERTY = "color"

/**
 * A thin outline under the colored route, so every color - the bright yellow middle included - stays
 * readable: dark on the light map style, light on the dark one.
 */
private val LightStyleCasingColor = Color(0x99212121)
private val DarkStyleCasingColor = Color(0xB3FFFFFF)

/** A gap (see [RouteSpeedColoring.isGap]): gray, dashed, no outline - clearly not a recorded stretch. */
private val LightStyleGapColor = Color(0xFF757575)
private val DarkStyleGapColor = Color(0xFFBDBDBD)
private const val START_LAYER_ID = "trip-route-start"
private const val FINISH_LAYER_ID = "trip-route-finish"

private const val SPOT_HIT_LAYER_ID = "trip-spots-hit"
private const val SPOT_LAYER_ID = "trip-spots"
private const val SPOT_NUMBER_LAYER_ID = "trip-spots-number"

/**
 * The feature properties of a spot marker: its database id (for the tap), its number (the label),
 * and minus its number, so the newest spot's label wins where two collide (labels with the lower
 * sort key are placed first).
 */
private const val SPOT_ID_PROPERTY = "spotId"
private const val SPOT_NUMBER_PROPERTY = "number"
private const val SPOT_LABEL_ORDER_PROPERTY = "labelOrder"

/**
 * A marked spot: a white disc with a dark green ring and its number - nothing like the red, yellow
 * and green route, the gray gaps, or the small solid start (green) and finish (red) dots, and the
 * same on the light and dark map styles (white stands out on both).
 */
private val SpotFillColor = Color.White
private val SpotRingAndNumberColor = Color(0xFF1B6D5A)
private val SPOT_RADIUS = 11.dp

/** Taps within this radius of a spot open it, so the 22 dp disc gets a finger-sized target. */
private val SPOT_TAP_RADIUS = 22.dp

/** A font the OpenFreeMap styles' glyph server has; MapLibre's default fonts aren't there, and the numbers would not draw. */
private const val SPOT_NUMBER_FONT = "Noto Sans Bold"

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
 * unavailable" message and a manual "Retry map" action - if the basemap fails to load (e.g. no
 * network). Retry only ever runs when the user taps it (never automatically/on a loop): toggling
 * [mapLoadFailed] back to `false` here leaves the live-map branch below and re-enters it, which -
 * being a plain Compose conditional, not a `key()`-stabilized call - disposes the previous (failed)
 * [LiveMap] instance and its underlying native map entirely and composes a brand-new one, so the
 * real style URL is attempted fresh rather than reusing whatever broke before. The failure is also
 * reset (without user action) whenever [points] or the light/dark style actually changes, so a
 * stale failure from a different trip or a theme switch never blocks an otherwise-healthy load.
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
) {
    require(points.isNotEmpty()) { "TripRouteMapCard requires at least one point; callers show their own empty state." }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        var mapLoadFailed by remember { mutableStateOf(false) }
        val isDark = isSystemInDarkTheme()

        LaunchedEffect(points, isDark) { mapLoadFailed = false }

        if (mapLoadFailed) {
            OfflineFallback(points = points, onRetry = { mapLoadFailed = false })
        } else {
            LiveMap(
                points = points,
                speedColoring = speedColoring,
                spots = spots,
                onSpotClick = onSpotClick,
                isDark = isDark,
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
    isDark: Boolean,
    onLoadFailed: () -> Unit,
) {
    val styleUrl = if (isDark) DARK_STYLE_URL else LIGHT_STYLE_URL
    val first = points.first()
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = Position(longitude = first.longitude, latitude = first.latitude), zoom = 14.0),
    )
    val fit = remember(points) { RouteCameraBounds.compute(points) }
    val mapDescription = stringResource(R.string.cd_trip_map_with_markers) +
        if (spots.isNotEmpty()) " " + stringResource(R.string.cd_trip_map_spots, spots.size) else ""
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(fit) { cameraState.showWholeRoute(fit, animate = false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
            baseStyle = BaseStyle.Uri(styleUrl),
            cameraState = cameraState,
            options = MapOptions(
                // Pan, zoom, double-tap and two-finger rotate stay on; MapLibre's compass appears once
                // the map is rotated, and tapping it (or "Show whole route") turns it back to north.
                // Only tilt is locked: in a map this small it mostly fires by accident while zooming.
                gestureOptions = GestureOptions(isTiltEnabled = false),
                // MapLibre's own scale bar shows feet on a US-region phone; MetricScaleBar below
                // replaces it. Logo, attribution and compass stay on.
                ornamentOptions = OrnamentOptions(isScaleBarEnabled = false),
            ),
            onMapLoadFailed = { onLoadFailed() },
        ) {
            if (speedColoring != null && speedColoring.runs.isNotEmpty()) {
                val runsSource = rememberGeoJsonSource(data = remember(speedColoring) { speedColoring.runsGeoJson() })
                LineLayer(
                    id = ROUTE_CASING_LAYER_ID,
                    source = runsSource,
                    color = const(if (isDark) DarkStyleCasingColor else LightStyleCasingColor),
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
                    color = const(MaterialTheme.colorScheme.primary),
                    width = const(4.dp),
                )
            }
            if (speedColoring != null && speedColoring.gaps.isNotEmpty()) {
                val gapsSource = rememberGeoJsonSource(data = remember(speedColoring) { speedColoring.gapsGeoJson() })
                LineLayer(
                    id = GAP_LAYER_ID,
                    source = gapsSource,
                    color = const(if (isDark) DarkStyleGapColor else LightStyleGapColor),
                    width = const(3.dp),
                    dasharray = const(listOf<Number>(2, 2)),
                )
            }

            val last = points.last()
            val startSource = rememberGeoJsonSource(
                data = GeoJsonData.Features(Point(longitude = first.longitude, latitude = first.latitude)),
            )
            CircleLayer(id = START_LAYER_ID, source = startSource, color = const(StartMarkerColor), radius = const(7.dp))

            val finishSource = rememberGeoJsonSource(
                data = GeoJsonData.Features(Point(longitude = last.longitude, latitude = last.latitude)),
            )
            CircleLayer(id = FINISH_LAYER_ID, source = finishSource, color = const(MaterialTheme.colorScheme.error), radius = const(7.dp))

            // Spots last, so they sit on top of the route and the start and finish dots.
            if (spots.isNotEmpty()) {
                val spotsSource = rememberGeoJsonSource(data = remember(spots) { spotsGeoJson(spots) })
                val onSpotFeaturesClick: FeaturesClickHandler = { features ->
                    val spotId = features.firstNotNullOfOrNull { it.properties?.get(SPOT_ID_PROPERTY)?.jsonPrimitive?.longOrNull }
                    if (spotId != null) {
                        onSpotClick(spotId)
                        ClickResult.Consume
                    } else {
                        ClickResult.Pass
                    }
                }
                // An almost invisible, bigger disc under each marker: the tap target.
                CircleLayer(
                    id = SPOT_HIT_LAYER_ID,
                    source = spotsSource,
                    color = const(SpotFillColor),
                    opacity = const(0.01f),
                    radius = const(SPOT_TAP_RADIUS),
                    onClick = onSpotFeaturesClick,
                )
                // Spots close together (a stop, a spring right after it) overlap at the whole-route
                // zoom: the newer one is drawn on top and only its number shows, so the numbers never
                // run into each other. Zooming in separates them.
                CircleLayer(
                    id = SPOT_LAYER_ID,
                    source = spotsSource,
                    sortKey = feature.get(SPOT_NUMBER_PROPERTY).asNumber(),
                    color = const(SpotFillColor),
                    radius = const(SPOT_RADIUS),
                    strokeColor = const(SpotRingAndNumberColor),
                    strokeWidth = const(2.5.dp),
                    onClick = onSpotFeaturesClick,
                )
                SymbolLayer(
                    id = SPOT_NUMBER_LAYER_ID,
                    source = spotsSource,
                    textField = format(span(feature.get(SPOT_NUMBER_PROPERTY).convertToString())),
                    textFont = const(listOf(SPOT_NUMBER_FONT)),
                    textSize = const(12.sp),
                    textColor = const(SpotRingAndNumberColor),
                    // Placed before the map's own labels (this layer is on top), which give way to it;
                    // the padding makes a label collide whenever the discs under two labels overlap.
                    sortKey = feature.get(SPOT_LABEL_ORDER_PROPERTY).asNumber(),
                    textPadding = const(5.dp),
                    onClick = onSpotFeaturesClick,
                )
            }
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
            onClick = { coroutineScope.launch { cameraState.showWholeRoute(fit, animate = true) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp),
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

private fun spotsGeoJson(spots: List<TripSpot>) = GeoJsonData.Features(
    FeatureCollection(
        spots.map { spot ->
            Feature(
                Point(longitude = spot.longitude, latitude = spot.latitude),
                buildJsonObject {
                    put(SPOT_ID_PROPERTY, spot.id)
                    put(SPOT_NUMBER_PROPERTY, spot.number)
                    put(SPOT_LABEL_ORDER_PROPERTY, -spot.number)
                },
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
private suspend fun CameraState.showWholeRoute(fit: RouteCameraBounds.Fit?, animate: Boolean) {
    when (fit) {
        is RouteCameraBounds.Fit.Bounded ->
            if (animate) {
                animateTo(boundingBox = fit.boundingBox, padding = ROUTE_PADDING)
            } else {
                jumpTo(boundingBox = fit.boundingBox, padding = ROUTE_PADDING)
            }
        is RouteCameraBounds.Fit.Centered -> {
            val target = CameraPosition(target = fit.target, zoom = fit.zoom)
            if (animate) animateTo(target) else position = target
        }
        null -> {}
    }
}

private val ROUTE_PADDING = PaddingValues(28.dp)

@Composable
private fun ColumnScope.OfflineFallback(points: List<TripPoint>, onRetry: () -> Unit) {
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
}
