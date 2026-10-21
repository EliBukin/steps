package com.example.stepsplit.ui.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stepsplit.domain.model.TripSpot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point

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
 * A trip's marked spots as numbered markers - on the trip screen's map ([TripRouteMapCard]) and on
 * the Open map screen. Put it after the route's layers, so the spots sit on top of the route.
 */
@Composable
@MaplibreComposable
internal fun SpotMarkerLayers(
    spots: List<TripSpot>,
    /**
     * Whether to draw each spot's number. Numbers need the map server's fonts, so a map drawn on a
     * plain offline background leaves them out.
     */
    showNumbers: Boolean = true,
    /** A spot marker was tapped, with that spot's id; null when a tap does nothing. */
    onSpotClick: ((Long) -> Unit)? = null,
) {
    val spotsSource = rememberGeoJsonSource(data = remember(spots) { spotsGeoJson(spots) })
    val onSpotFeaturesClick: FeaturesClickHandler? = onSpotClick?.let { onClick ->
        { features ->
            val spotId = features.firstNotNullOfOrNull { it.properties?.get(SPOT_ID_PROPERTY)?.jsonPrimitive?.longOrNull }
            if (spotId != null) {
                onClick(spotId)
                ClickResult.Consume
            } else {
                ClickResult.Pass
            }
        }
    }
    if (onSpotFeaturesClick != null) {
        // An almost invisible, bigger disc under each marker: the tap target.
        CircleLayer(
            id = SPOT_HIT_LAYER_ID,
            source = spotsSource,
            color = const(SpotFillColor),
            opacity = const(0.01f),
            radius = const(SPOT_TAP_RADIUS),
            onClick = onSpotFeaturesClick,
        )
    }
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
    if (showNumbers) {
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
