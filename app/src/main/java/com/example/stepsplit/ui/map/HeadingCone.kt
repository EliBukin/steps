package com.example.stepsplit.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import com.example.stepsplit.data.trip.MapFix
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point

/** The cone's reach from the dot's center, and half its width. */
private val CONE_RADIUS = 44.dp
private const val CONE_HALF_ANGLE_DEGREES = 28f

/**
 * Which way the phone points: a soft cone fanning out from the location dot toward [heading]
 * (degrees from true north; null draws nothing), in [color]. Turns with the map, so it always points
 * the right way on it. Put it before the location dot's layers, so the dot sits on top. [heading] is
 * read here, so a new heading (many a second) redraws only the cone, not the rest of the map's layers.
 */
@Composable
@MaplibreComposable
internal fun HeadingCone(id: String, location: MapFix?, heading: () -> Float?, color: Color) {
    val degrees = heading()
    val source = rememberGeoJsonSource(
        data = remember(location?.latitude, location?.longitude) {
            GeoJsonData.Features(
                if (location == null) {
                    FeatureCollection()
                } else {
                    FeatureCollection(Feature(geometry = Point(longitude = location.longitude, latitude = location.latitude), properties = buildJsonObject {}))
                },
            )
        },
    )
    val conePainter = rememberVectorPainter(remember(color) { coneImage(color) })
    SymbolLayer(
        id = id,
        source = source,
        visible = location != null && degrees != null,
        iconImage = image(conePainter),
        iconAnchor = const(SymbolAnchor.Center),
        iconRotate = const(degrees ?: 0f),
        iconRotationAlignment = const(IconRotationAlignment.Map),
        iconPitchAlignment = const(IconPitchAlignment.Map),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
    )
}

/** A fan pointing up from the image's center, strongest near the center and fading out. */
private fun coneImage(color: Color): ImageVector {
    val size = CONE_RADIUS * 2
    val r = CONE_RADIUS.value
    val half = CONE_HALF_ANGLE_DEGREES * PI.toFloat() / 180f
    return ImageVector.Builder(defaultWidth = size, defaultHeight = size, viewportWidth = 2 * r, viewportHeight = 2 * r, autoMirror = false)
        .path(
            fill = Brush.radialGradient(
                0f to color.copy(alpha = 0.55f),
                1f to color.copy(alpha = 0f),
                center = Offset(r, r),
                radius = r,
            ),
        ) {
            moveTo(r, r)
            lineTo(r + r * sin(-half), r - r * cos(-half))
            arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = r + r * sin(half), y1 = r - r * cos(half))
            close()
        }
        .build()
}

/**
 * A compass needle for the "map turns with me" button: red toward north, [southColor] the other
 * way, turned by the map's [bearing] so it points at north on the map (read while drawing, so a
 * turning map only redraws the needle). Never mirrored in right-to-left languages.
 */
@Composable
internal fun CompassNeedle(bearing: () -> Float, southColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp).graphicsLayer { rotationZ = -bearing() }) {
        val cx = size.width / 2
        val tip = size.height * 0.08f
        val halfWidth = size.width * 0.16f
        val north = Path().apply {
            moveTo(cx, tip)
            lineTo(cx + halfWidth, size.height / 2)
            lineTo(cx - halfWidth, size.height / 2)
            close()
        }
        val south = Path().apply {
            moveTo(cx, size.height - tip)
            lineTo(cx + halfWidth, size.height / 2)
            lineTo(cx - halfWidth, size.height / 2)
            close()
        }
        drawPath(north, NeedleNorthColor)
        drawPath(south, southColor)
    }
}

private val NeedleNorthColor = Color(0xFFD32F2F)
