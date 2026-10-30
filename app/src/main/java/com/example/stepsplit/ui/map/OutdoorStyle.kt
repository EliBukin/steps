package com.example.stepsplit.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.stepsplit.R
import com.example.stepsplit.data.mappack.InstalledMapPack
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.style.BaseStyle

/** The offline map packs on the phone, provided at the app's root; none in previews and tests. */
internal val LocalMapPacks = compositionLocalOf<List<InstalledMapPack>> { emptyList() }

/** The map's background and whether it comes from an offline pack. */
internal class MapBackground(val style: BaseStyle, val fromPack: Boolean)

/**
 * The background of the trip screen's map and the Map tab:
 * - an offline pack's map ([MapPackStyle]) while the camera's center is inside its area - from
 *   [MapPackChoice.MIN_ZOOM] in, or at any zoom once the online map failed to load;
 * - else the outdoor style ([MapStyles.OUTDOOR_STYLE_ASSET]) online;
 * - else, once that failed to load ([onlineFailed]), the plain offline background.
 * Labels are in the app's language either way. Crossing a pack's edge switches the style once the
 * camera has settled for a moment, not on every frame of a pan.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberMapBackground(cameraState: CameraState, onlineFailed: Boolean = false): MapBackground {
    val context = LocalContext.current
    val labelLanguage = stringResource(R.string.map_label_language)
    val packs = LocalMapPacks.current
    val currentOnlineFailed by rememberUpdatedState(onlineFailed)

    fun choose() = cameraState.position.let { MapPackChoice.packFor(packs, it.target.longitude, it.target.latitude, it.zoom, currentOnlineFailed) }

    var pack by remember(packs) { mutableStateOf(choose()) }
    LaunchedEffect(packs, cameraState) {
        snapshotFlow { choose() }.distinctUntilChanged().debounce(MapPackChoice.SETTLE_MILLIS).collect { pack = it }
    }
    // A failed online map switches at once: the pack is all there is to show.
    LaunchedEffect(onlineFailed) { if (onlineFailed) pack = choose() }

    val chosen = pack
    return remember(labelLanguage, chosen, onlineFailed) {
        when {
            chosen != null -> {
                val assetJson = context.assets.open(MapStyles.OUTDOOR_STYLE_ASSET).bufferedReader().use { it.readText() }
                MapBackground(BaseStyle.Json(MapStyles.outdoorStyleJson(MapPackStyle.styleJson(assetJson, chosen), labelLanguage)), fromPack = true)
            }
            onlineFailed -> MapBackground(BaseStyle.Json(MapStyles.offlineStyleJson()), fromPack = false)
            else -> {
                val assetJson = context.assets.open(MapStyles.OUTDOOR_STYLE_ASSET).bufferedReader().use { it.readText() }
                MapBackground(BaseStyle.Json(MapStyles.outdoorStyleJson(assetJson, labelLanguage)), fromPack = false)
            }
        }
    }
}

/** Which offline pack, if any, the map draws from; pure, for the JVM tests. */
internal object MapPackChoice {
    /**
     * Zoomed out further, the online map shows the neighboring countries too, which a pack doesn't
     * have: from about here in, a phone's screen shows little outside the pack.
     */
    const val MIN_ZOOM = 7.0

    /** How long the camera stays on the other side of a pack's edge before the map switches. */
    const val SETTLE_MILLIS = 400L

    fun packFor(packs: List<InstalledMapPack>, longitude: Double, latitude: Double, zoom: Double, onlineFailed: Boolean): InstalledMapPack? {
        if (zoom < MIN_ZOOM && !onlineFailed) return null
        return packs.firstOrNull { it.area.contains(longitude, latitude) }
    }
}

/**
 * What the app draws on the map, in colors chosen for the light outdoor style - the map stays light in
 * dark mode too, so these never follow the app's theme.
 */
internal object OnMapColors {
    /** A thin dark outline under a route, so every route color - the bright yellow middle included - stays readable. */
    val RouteCasing = Color(0x99212121)

    /** A gap in a route: gray, dashed, no outline - clearly not a recorded stretch. */
    val Gap = Color(0xFF757575)

    /** The start dot (as on the offline route trace) and the finish dot. */
    val Start = Color(0xFF2E7D32)
    val Finish = Color(0xFFBA1A1A)

    /** A route without speed colors: the app's light-theme primary color. */
    val PlainRoute = Color(0xFF1B6D5A)
}
