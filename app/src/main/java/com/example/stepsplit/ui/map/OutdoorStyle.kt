package com.example.stepsplit.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.stepsplit.R
import org.maplibre.compose.style.BaseStyle

/**
 * The outdoor map style ([MapStyles.OUTDOOR_STYLE_ASSET]) with its labels in the app's language, for
 * the trip screen's map and the Map tab. Read from the app's assets when a map first shows.
 */
@Composable
internal fun rememberOutdoorStyle(): BaseStyle {
    val context = LocalContext.current
    val labelLanguage = stringResource(R.string.map_label_language)
    return remember(labelLanguage) {
        val assetJson = context.assets.open(MapStyles.OUTDOOR_STYLE_ASSET).bufferedReader().use { it.readText() }
        BaseStyle.Json(MapStyles.outdoorStyleJson(assetJson, labelLanguage))
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
