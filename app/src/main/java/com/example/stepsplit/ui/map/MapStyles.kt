package com.example.stepsplit.ui.map

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The map backgrounds of the trip screen's map and the Open map screen. */
internal object MapStyles {
    /**
     * OpenFreeMap (https://openfreemap.org) - a free, keyless vector tile provider with no request
     * limits and no account/API key, explicitly permitting commercial and hobby app use. Style choice
     * follows the system light/dark setting; both styles carry their own OSM/OpenMapTiles attribution,
     * shown automatically by MapLibre's built-in attribution control (see `MaplibreMap`'s default
     * `MapOptions.ornamentOptions`, never disabled here). Only ever requested while a map is on
     * screen - the trip screen's or the Open map screen's - no prefetching, no offline pack download,
     * no background loading.
     */
    private const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
    private const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

    /** Close to the online styles' own land color, so the switch to the offline background isn't jarring. */
    private const val OFFLINE_LIGHT_BACKGROUND = "#E9ECE7"
    private const val OFFLINE_DARK_BACKGROUND = "#202423"

    fun styleUrl(isDark: Boolean): String = if (isDark) DARK_STYLE_URL else LIGHT_STYLE_URL

    /**
     * The Open map's background without internet: one plain color and nothing else. It has no
     * tiles, fonts ("glyphs") or icons ("sprite") to download, so it draws offline at once, and the
     * location dot, the route and the spots still draw on it - the spots without their numbers,
     * which need the map server's fonts.
     */
    fun offlineStyleJson(isDark: Boolean): String = buildJsonObject {
        put("version", 8)
        put("name", "Offline")
        putJsonObject("sources") {}
        putJsonArray("layers") {
            addJsonObject {
                put("id", "offline-background")
                put("type", "background")
                putJsonObject("paint") {
                    put("background-color", if (isDark) OFFLINE_DARK_BACKGROUND else OFFLINE_LIGHT_BACKGROUND)
                }
            }
        }
    }.toString()
}
