package com.example.stepsplit.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.stepsplit.domain.route.RouteWaypoint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

/**
 * Imported routes in magenta - the color hiking and flying GPS units use for the course to follow,
 * and one nothing else on the map uses: recorded trips are red, yellow and green by speed, the trip
 * being recorded is orange, the offline map's marked trails are red, blue, green, black, orange,
 * purple, yellow and brown, the dot is blue and spots are teal.
 *
 * A route is a see-through band with thin darker edges, wider than a trail: a marked trail under
 * it still shows its color in the middle, which is what you follow on the ground.
 */
internal object ImportedRouteColors {
    val Route = Color(0xFFD4148C)
    val Edge = Color(0xFF9E0F68)
    val Label = Color(0xFF7A0B50)
    val Halo = Color.White
}

private const val BAND_LAYER_ID = "imported-route-band"
private const val EDGE_LAYER_ID = "imported-route-edges"
private const val START_LAYER_ID = "imported-route-starts"
private const val END_LAYER_ID = "imported-route-ends"
private const val WAYPOINT_HIT_LAYER_ID = "imported-route-waypoints-hit"
private const val WAYPOINT_LAYER_ID = "imported-route-waypoints"
private const val WAYPOINT_NAME_LAYER_ID = "imported-route-waypoint-names"

private const val ROUTE_ID_PROPERTY = "routeId"
private const val WAYPOINT_INDEX_PROPERTY = "index"
private const val NAME_PROPERTY = "name"

private val BAND_WIDTH = 9.dp
private val EDGE_WIDTH = 1.2.dp
private val END_RADIUS = 6.dp
private val WAYPOINT_RADIUS = 5.dp

/** Taps within this radius of a waypoint open it: a finger-sized target for the small pin. */
private val WAYPOINT_TAP_RADIUS = 20.dp

/** The map server's and the offline map's bold font (see SpotMarkerLayers). */
private const val NAME_FONT = "Noto Sans Bold"

/**
 * The shown imported routes on the Map tab: each line as a band, its start (a white dot with a
 * magenta ring) and its end (a magenta dot), and the waypoints as small pins with their names.
 * Put it before the trip being recorded and the dot, so those stay on top.
 */
@Composable
@MaplibreComposable
internal fun ImportedRouteLayers(
    routes: List<ImportedRouteOnMap>,
    /** Names need the map's fonts: a map drawn on the plain offline background leaves them out. */
    showNames: Boolean,
    /** A waypoint was tapped: its route's id and its index among that route's waypoints. */
    onWaypointClick: (routeId: Long, index: Int) -> Unit,
) {
    val linesSource = rememberGeoJsonSource(data = remember(routes) { linesGeoJson(routes) })
    LineLayer(
        id = BAND_LAYER_ID,
        source = linesSource,
        color = const(ImportedRouteColors.Route),
        opacity = const(0.4f),
        width = const(BAND_WIDTH),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )
    // Two thin lines along the band's sides (a line with a gap as wide as the band).
    LineLayer(
        id = EDGE_LAYER_ID,
        source = linesSource,
        color = const(ImportedRouteColors.Edge),
        opacity = const(0.85f),
        width = const(EDGE_WIDTH),
        gapWidth = const(BAND_WIDTH),
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
    )

    val startsSource = rememberGeoJsonSource(data = remember(routes) { endsGeoJson(routes, start = true) })
    CircleLayer(
        id = START_LAYER_ID,
        source = startsSource,
        color = const(Color.White),
        radius = const(END_RADIUS),
        strokeColor = const(ImportedRouteColors.Route),
        strokeWidth = const(3.dp),
    )
    val endsSource = rememberGeoJsonSource(data = remember(routes) { endsGeoJson(routes, start = false) })
    CircleLayer(
        id = END_LAYER_ID,
        source = endsSource,
        color = const(ImportedRouteColors.Route),
        radius = const(END_RADIUS),
        strokeColor = const(Color.White),
        strokeWidth = const(2.5.dp),
    )

    if (routes.none { it.waypoints.isNotEmpty() }) return
    val waypointsSource = rememberGeoJsonSource(data = remember(routes) { waypointsGeoJson(routes) })
    val onClick: FeaturesClickHandler = { features ->
        val properties = features.firstNotNullOfOrNull { it.properties?.takeIf { p -> p.containsKey(WAYPOINT_INDEX_PROPERTY) } }
        val routeId = properties?.get(ROUTE_ID_PROPERTY)?.jsonPrimitive?.longOrNull
        val index = properties?.get(WAYPOINT_INDEX_PROPERTY)?.jsonPrimitive?.intOrNull
        if (routeId != null && index != null) {
            onWaypointClick(routeId, index)
            ClickResult.Consume
        } else {
            ClickResult.Pass
        }
    }
    // An almost invisible, bigger disc under each pin: the tap target.
    CircleLayer(
        id = WAYPOINT_HIT_LAYER_ID,
        source = waypointsSource,
        color = const(Color.White),
        opacity = const(0.01f),
        radius = const(WAYPOINT_TAP_RADIUS),
        onClick = onClick,
    )
    CircleLayer(
        id = WAYPOINT_LAYER_ID,
        source = waypointsSource,
        color = const(ImportedRouteColors.Route),
        radius = const(WAYPOINT_RADIUS),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
        onClick = onClick,
    )
    if (showNames) {
        SymbolLayer(
            id = WAYPOINT_NAME_LAYER_ID,
            source = waypointsSource,
            textField = format(span(feature.get(NAME_PROPERTY).convertToString())),
            textFont = const(listOf(NAME_FONT)),
            textSize = const(12.sp),
            textColor = const(ImportedRouteColors.Label),
            textHaloColor = const(ImportedRouteColors.Halo),
            textHaloWidth = const(1.5.dp),
            textAnchor = const(SymbolAnchor.Left),
            textOffset = offset(0.9.em, 0.em),
            onClick = onClick,
        )
    }
}

/** Every segment of every shown route as one line; nothing joins a segment to the next. */
private fun linesGeoJson(routes: List<ImportedRouteOnMap>) = GeoJsonData.Features(
    FeatureCollection(
        routes.flatMap { route ->
            route.lines.flatMap { line ->
                line.segments.map { segment ->
                    Feature(
                        LineString(List(segment.size) { Position(longitude = segment.longitudes[it], latitude = segment.latitudes[it]) }),
                        buildJsonObject { put(ROUTE_ID_PROPERTY, route.id) },
                    )
                }
            }
        },
    ),
)

/** Where each line starts ([start]) or ends. */
private fun endsGeoJson(routes: List<ImportedRouteOnMap>, start: Boolean) = GeoJsonData.Features(
    FeatureCollection(
        routes.flatMap { route ->
            route.lines.map { line ->
                val segment = if (start) line.segments.first() else line.segments.last()
                val i = if (start) 0 else segment.size - 1
                Feature(Point(longitude = segment.longitudes[i], latitude = segment.latitudes[i]), buildJsonObject { put(ROUTE_ID_PROPERTY, route.id) })
            }
        },
    ),
)

private fun waypointsGeoJson(routes: List<ImportedRouteOnMap>) = GeoJsonData.Features(
    FeatureCollection(
        routes.flatMap { route ->
            route.waypoints.mapIndexed { index, waypoint: RouteWaypoint ->
                Feature(
                    Point(longitude = waypoint.longitude, latitude = waypoint.latitude),
                    buildJsonObject {
                        put(ROUTE_ID_PROPERTY, route.id)
                        put(WAYPOINT_INDEX_PROPERTY, index)
                        put(NAME_PROPERTY, waypoint.name.orEmpty())
                    },
                )
            }
        },
    ),
)
