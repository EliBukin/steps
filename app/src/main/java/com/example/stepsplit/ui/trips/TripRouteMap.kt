package com.example.stepsplit.ui.trips

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.trip.RoutePoint
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
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
private const val START_LAYER_ID = "trip-route-start"
private const val FINISH_LAYER_ID = "trip-route-finish"

/**
 * The completed-trip route on a real geographic basemap, in a rounded Material 3 card: fits the
 * camera to the full route with padding, draws the route as a polyline, and marks the start and
 * finish distinctly. Reads only the already-recorded [points] (must be non-empty - the caller
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
fun TripRouteMapCard(points: List<TripPoint>, modifier: Modifier = Modifier) {
    require(points.isNotEmpty()) { "TripRouteMapCard requires at least one point; callers show their own empty state." }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        var mapLoadFailed by remember { mutableStateOf(false) }
        val isDark = isSystemInDarkTheme()

        LaunchedEffect(points, isDark) { mapLoadFailed = false }

        if (mapLoadFailed) {
            OfflineFallback(points = points, onRetry = { mapLoadFailed = false })
        } else {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                LiveMap(points = points, isDark = isDark, onLoadFailed = { mapLoadFailed = true })
            }
        }
    }
}

@Composable
private fun LiveMap(points: List<TripPoint>, isDark: Boolean, onLoadFailed: () -> Unit) {
    val styleUrl = if (isDark) DARK_STYLE_URL else LIGHT_STYLE_URL
    val first = points.first()
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(target = Position(longitude = first.longitude, latitude = first.latitude), zoom = 14.0),
    )
    val fit = remember(points) { RouteCameraBounds.compute(points) }
    val mapDescription = stringResource(R.string.cd_trip_map_with_markers)

    LaunchedEffect(fit) {
        when (fit) {
            is RouteCameraBounds.Fit.Bounded -> cameraState.jumpTo(boundingBox = fit.boundingBox, padding = PaddingValues(28.dp))
            is RouteCameraBounds.Fit.Centered -> cameraState.position = CameraPosition(target = fit.target, zoom = fit.zoom)
            null -> {}
        }
    }

    MaplibreMap(
        modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
        baseStyle = BaseStyle.Uri(styleUrl),
        cameraState = cameraState,
        onMapLoadFailed = { onLoadFailed() },
    ) {
        if (points.size >= 2) {
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

        val last = points.last()
        val startSource = rememberGeoJsonSource(
            data = GeoJsonData.Features(Point(longitude = first.longitude, latitude = first.latitude)),
        )
        CircleLayer(id = START_LAYER_ID, source = startSource, color = const(StartMarkerColor), radius = const(7.dp))

        val finishSource = rememberGeoJsonSource(
            data = GeoJsonData.Features(Point(longitude = last.longitude, latitude = last.latitude)),
        )
        CircleLayer(id = FINISH_LAYER_ID, source = finishSource, color = const(MaterialTheme.colorScheme.error), radius = const(7.dp))
    }
}

@Composable
private fun OfflineFallback(points: List<TripPoint>, onRetry: () -> Unit) {
    // RouteTraceCanvas imposes its own square aspect ratio, so it isn't wrapped further here.
    RouteTraceCanvas(points = points.map { RoutePoint(latitude = it.latitude, longitude = it.longitude) })
    Text(
        text = stringResource(R.string.trip_detail_map_unavailable_message),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
    TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.trip_detail_map_retry_action))
    }
}
