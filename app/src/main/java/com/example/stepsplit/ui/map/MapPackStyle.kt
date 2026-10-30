package com.example.stepsplit.ui.map

import com.example.stepsplit.data.mappack.InstalledMapPack
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The outdoor style ([MapStyles.OUTDOOR_STYLE_ASSET]) drawn from a downloaded offline pack
 * ([InstalledMapPack]) instead of OpenFreeMap and Mapterhorn: nothing in it touches the network.
 *
 * - The vector and hill-shading sources read the pack's two PMTiles files; fonts ("glyphs") and
 *   icons ("sprite") come from the app's assets.
 * - OpenMapTiles leaves tracks, paths, minor roads and wadis out of its low zoom levels; the pack's
 *   outdoor layers (`outdoor_transportation`, `outdoor_waterway`, built by
 *   scripts/offline-maps/outdoor.yml with the same attribute names) fill them in. Each is drawn by
 *   a copy of the style's own layer, reading the outdoor layer up to the zoom where OpenMapTiles
 *   takes over, so both look the same.
 * - Two layers the online map doesn't have: marked hiking trails in their trail-mark colors
 *   (`trail`) and contour lines (`contour`, with a height label every 100 m).
 * - Camp sites from z11 (`outdoor_poi`), drawn by a copy of the style's own outdoor POI layer.
 *
 * Every other layer, color and label rule is the outdoor style's own, so a change there shows on
 * both maps.
 */
internal object MapPackStyle {
    const val GLYPHS_URL = "asset://map/glyphs/{fontstack}/{range}.pbf"
    const val SPRITE_URL = "asset://map/sprite/ofm"

    /** The source the copied and added layers read; the pack has one vector file for everything. */
    private const val VECTOR_SOURCE = "openmaptiles"
    private const val DEM_SOURCE = "mapterhorn"

    /**
     * The same vector file, read only up to z13: contours and camp sites are built to z13 (to z14
     * they'd be a much bigger download), and from z14 MapLibre stretches this source's z13 tiles
     * instead of reading the file's z14 tiles, which don't have them.
     */
    private const val Z13_SOURCE = "pack-z13"

    fun styleJson(outdoorStyleJson: String, pack: InstalledMapPack): String {
        val style = Json.parseToJsonElement(outdoorStyleJson).jsonObject
        val layers = style.getValue("layers").jsonArray.map { it.jsonObject }.toMutableList()
        val byId = layers.associateBy { it.id }

        fun clone(
            id: String,
            newId: String,
            sourceLayer: String,
            minzoom: Int,
            maxzoom: Int,
            onlyClasses: List<String>? = null,
            source: String = VECTOR_SOURCE,
        ): JsonObject {
            val original = byId.getValue(id)
            val filter = if (onlyClasses == null) original.getValue("filter") else allOf(original.getValue("filter"), classIn(onlyClasses))
            return JsonObject(
                original - "minzoom" + mapOf(
                    "id" to JsonPrimitive(newId),
                    "source" to JsonPrimitive(source),
                    "source-layer" to JsonPrimitive(sourceLayer),
                    "minzoom" to JsonPrimitive(minzoom),
                    "maxzoom" to JsonPrimitive(maxzoom),
                    "filter" to filter,
                ),
            )
        }

        // Wadis and streams: OpenMapTiles has streams from z13 (rivers from z12).
        layers.insertAfter(
            "waterway-intermittent",
            clone("waterway-stream", "pack-waterway-stream", "outdoor_waterway", 10, 13),
            clone("waterway-intermittent", "pack-waterway-intermittent", "outdoor_waterway", 9, 13),
        )
        // Tracks and paths: OpenMapTiles has them from z14 (named ones from z13). Unpaved tertiary
        // and unclassified roads draw as tracks, as in the outdoor style.
        layers.insertAfter(
            "track-paved",
            clone("track", "pack-track", "outdoor_transportation", 10, 14, listOf("track")),
            clone("track", "pack-track-unpaved-tertiary", "outdoor_transportation", 10, 11, listOf("tertiary")),
            clone("track", "pack-track-unpaved-minor", "outdoor_transportation", 10, 12, listOf("minor")),
            clone("track-paved", "pack-track-paved", "outdoor_transportation", 10, 14, listOf("track")),
            *trailLayers(),
        )
        layers.insertAfter("path", clone("path", "pack-path", "outdoor_transportation", 11, 14))
        // Tertiary roads (Road 171) from z9 and unclassified ones from z10; OpenMapTiles has them
        // from z11 and z12. Drawn wider than the outdoor style's hair-thin low-zoom roads: in the
        // desert they're what you find your way by.
        layers.insertBefore(
            "road-casing",
            clone("road-casing", "pack-road-casing-tertiary", "outdoor_transportation", 9, 11, listOf("tertiary")).withWidth(9, 2.3, 3.5),
            clone("road-casing", "pack-road-casing-minor", "outdoor_transportation", 10, 12, listOf("minor")).withWidth(10, 1.9, 3.1),
        )
        layers.insertBefore(
            "road",
            clone("road", "pack-road-tertiary", "outdoor_transportation", 9, 11, listOf("tertiary")).withWidth(9, 0.9, 1.7),
            clone("road", "pack-road-minor", "outdoor_transportation", 10, 12, listOf("minor")).withWidth(10, 0.7, 1.5),
        )
        // Camp sites (night camps) from z11, with the style's camp icon; OpenMapTiles has them from z14.
        layers.insertAfter("poi-outdoor", clone("poi-outdoor", "pack-poi-campsite", "outdoor_poi", 11, 14, source = Z13_SOURCE))
        layers.insertAfter("hillshade", CONTOUR_LINES)
        layers.insertAfter("waterway-label", CONTOUR_LABELS)

        val sources = style.getValue("sources").jsonObject + mapOf(
            VECTOR_SOURCE to JsonObject(mapOf("type" to JsonPrimitive("vector"), "url" to JsonPrimitive(pmtilesUrl(pack.vectorFile)))),
            Z13_SOURCE to JsonObject(
                mapOf("type" to JsonPrimitive("vector"), "url" to JsonPrimitive(pmtilesUrl(pack.vectorFile)), "maxzoom" to JsonPrimitive(13)),
            ),
            DEM_SOURCE to JsonObject(
                mapOf(
                    "type" to JsonPrimitive("raster-dem"),
                    "url" to JsonPrimitive(pmtilesUrl(pack.demFile)),
                    "tileSize" to JsonPrimitive(512),
                    "encoding" to JsonPrimitive("terrarium"),
                ),
            ),
        )
        return JsonObject(
            style + mapOf(
                "sources" to JsonObject(sources),
                "glyphs" to JsonPrimitive(GLYPHS_URL),
                "sprite" to JsonPrimitive(SPRITE_URL),
                "layers" to JsonArray(layers),
            ),
        ).toString()
    }

    fun pmtilesUrl(file: File) = "pmtiles://file://${file.absolutePath}"

    private val JsonObject.id get() = getValue("id").jsonPrimitive.content

    private fun MutableList<JsonObject>.insertAfter(id: String, vararg new: JsonObject) = addAll(indexOfFirst { it.id == id } + 1, new.toList())

    private fun MutableList<JsonObject>.insertBefore(id: String, vararg new: JsonObject) = addAll(indexOfFirst { it.id == id }, new.toList())

    private fun allOf(vararg filters: JsonElement) = JsonArray(listOf(JsonPrimitive("all")) + filters)

    private fun classIn(classes: List<String>) = json("""["match", ["get", "class"], ${classes.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}, true, false]""")

    /** The layer's line width from [width] at [fromZoom] to [widthAtZ12] at z12. */
    private fun JsonObject.withWidth(fromZoom: Int, width: Double, widthAtZ12: Double): JsonObject {
        val paint = getValue("paint").jsonObject + ("line-width" to json("""["interpolate", ["linear"], ["zoom"], $fromZoom, $width, 12, $widthAtZ12]"""))
        return JsonObject(this + ("paint" to JsonObject(paint)))
    }

    private fun json(text: String) = Json.parseToJsonElement(text)

    /** Marked hiking trails (OSM hiking routes) in their trail-mark color, over a thin white line. */
    private fun trailLayers(): Array<JsonObject> = arrayOf(
        json(
            """{"id": "pack-trail-casing", "type": "line", "source": "$VECTOR_SOURCE", "source-layer": "trail", "minzoom": 11,
            "layout": {"line-cap": "round", "line-join": "round"},
            "paint": {"line-color": "#FFFFFF", "line-opacity": 0.8, "line-width": ["interpolate", ["linear"], ["zoom"], 11, 2.2, 14, 4.5, 17, 7]}}""",
        ).jsonObject,
        json(
            """{"id": "pack-trail", "type": "line", "source": "$VECTOR_SOURCE", "source-layer": "trail", "minzoom": 11,
            "layout": {"line-cap": "round", "line-join": "round"},
            "paint": {"line-color": ["match", ["get", "color"], "red", "#D7301F", "blue", "#1F5FBF", "green", "#2E8B3A", "black", "#2B2B2B",
                "orange", "#F08A00", "purple", "#8E44AD", "yellow", "#E3B505", "brown", "#8B5A2B", "#7A7A7A"],
              "line-opacity": 0.9, "line-width": ["interpolate", ["linear"], ["zoom"], 11, 1.0, 14, 2.4, 17, 4]}}""",
        ).jsonObject,
    )

    /** Every 50 m from z12, every 10 m from z13 (the pack has them so); each 100 m a little darker. */
    private val CONTOUR_LINES = json(
        """{"id": "pack-contour", "type": "line", "source": "$Z13_SOURCE", "source-layer": "contour", "minzoom": 12,
        "paint": {"line-color": "#A0784A", "line-opacity": ["match", ["get", "index"], 1, 0.55, 0.35],
          "line-width": ["interpolate", ["linear"], ["zoom"], 12, ["match", ["get", "index"], 1, 0.7, 0.4], 15, ["match", ["get", "index"], 1, 1.2, 0.6]]}}""",
    ).jsonObject

    private val CONTOUR_LABELS = json(
        """{"id": "pack-contour-label", "type": "symbol", "source": "$Z13_SOURCE", "source-layer": "contour", "minzoom": 13,
        "filter": ["==", ["get", "index"], 1],
        "layout": {"symbol-placement": "line", "text-field": ["to-string", ["get", "ele"]], "text-font": ["Noto Sans Regular"],
          "text-size": 10, "symbol-spacing": 400},
        "paint": {"text-color": "#8A6A44", "text-halo-color": "${MapStyles.LAND_COLOR}", "text-halo-width": 1.2}}""",
    ).jsonObject
}
