package com.example.stepsplit.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The map background of the trip screen's map and the Map tab. */
internal object MapStyles {
    /**
     * The app's own outdoor style, shipped inside the app: tracks, paths, wadis, peaks and desert
     * ground made easy to read, on OpenFreeMap's vector tiles (https://openfreemap.org - free,
     * keyless, no request limits, explicitly fine for apps). Its map data, fonts ("glyphs") and icons
     * ("sprite") come from OpenFreeMap's server, as before; the style itself is no longer downloaded.
     * The same light style in light and dark mode: it reads better outdoors, and the route's colors are
     * tuned for it. The OSM / OpenMapTiles / OpenFreeMap attribution comes with the tiles and is shown
     * by MapLibre's built-in attribution button (see `MaplibreMap`'s default
     * `MapOptions.ornamentOptions`, never disabled here). Only ever requested while a map is on
     * screen - the trip screen's or the Map tab's - no prefetching, no offline pack download, no
     * background loading.
     */
    const val OUTDOOR_STYLE_ASSET = "map/outdoor-style.json"

    /** The outdoor style's land color; the offline background uses it too, so the switch isn't jarring. */
    const val LAND_COLOR = "#F2EFE6"

    /** Every label in [OUTDOOR_STYLE_ASSET]: the English name, else the Latin-script one, else the local one. */
    private val ENGLISH_LABEL: JsonElement =
        Json.parseToJsonElement("""["coalesce", ["get", "name:en"], ["get", "name:latin"], ["get", "name"]]""")

    /** The Hebrew name, else the local one (often Hebrew already in Israel, Arabic or English elsewhere). */
    private val HEBREW_LABEL: JsonElement = Json.parseToJsonElement("""["coalesce", ["get", "name:he"], ["get", "name"]]""")

    /**
     * The outdoor style ([OUTDOOR_STYLE_ASSET]'s text, [assetJson]) with its labels in the app's
     * language: Hebrew names for "he", the style's own English labels otherwise.
     */
    fun outdoorStyleJson(assetJson: String, labelLanguage: String): String =
        if (labelLanguage == "he" || labelLanguage == "iw") {
            Json.parseToJsonElement(assetJson).replacing(ENGLISH_LABEL, HEBREW_LABEL).toString()
        } else {
            assetJson
        }

    /**
     * The Map tab's background when the outdoor style can't load: one plain color and nothing else.
     * It has no tiles, fonts ("glyphs") or icons ("sprite") to download, so it draws offline at once,
     * and the location dot, the route and the spots still draw on it - the spots without their
     * numbers, which need the map server's fonts.
     */
    fun offlineStyleJson(): String = buildJsonObject {
        put("version", 8)
        put("name", "Offline")
        putJsonObject("sources") {}
        putJsonArray("layers") {
            addJsonObject {
                put("id", "offline-background")
                put("type", "background")
                putJsonObject("paint") { put("background-color", LAND_COLOR) }
            }
        }
    }.toString()

    private fun JsonElement.replacing(old: JsonElement, new: JsonElement): JsonElement = when {
        this == old -> new
        this is JsonObject -> JsonObject(mapValues { (_, value) -> value.replacing(old, new) })
        this is JsonArray -> JsonArray(map { it.replacing(old, new) })
        else -> this
    }
}
