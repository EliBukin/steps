package com.example.stepsplit.ui.map

import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStylesTest {

    /** The style as the app ships it (unit tests run in the app module's folder). */
    private val assetJson = File("src/main/assets/${MapStyles.OUTDOOR_STYLE_ASSET}").readText()
    private val style = Json.parseToJsonElement(assetJson).jsonObject
    private val layers = style.getValue("layers").jsonArray.map { it.jsonObject }
    private val sources = style.getValue("sources").jsonObject

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun layer(id: String) = layers.single { it.string("id") == id }
    private fun indexOf(id: String) = layers.indexOfFirst { it.string("id") == id }

    private fun JsonElement.strings(): List<String> = when (this) {
        is JsonPrimitive -> if (isString) listOf(content) else emptyList()
        is JsonArray -> flatMap { it.strings() }
        is JsonObject -> values.flatMap { it.strings() }
    }

    @Test
    fun `the offline background is a valid style with one background layer and nothing to download`() {
        val json = MapStyles.offlineStyleJson()
        val offline = Json.parseToJsonElement(json).jsonObject

        assertEquals(8, offline.getValue("version").jsonPrimitive.int)
        assertTrue(offline.getValue("sources").jsonObject.isEmpty())
        val offlineLayers = offline.getValue("layers").jsonArray
        assertEquals(1, offlineLayers.size)
        assertEquals("background", offlineLayers.single().jsonObject.string("type"))
        // No fonts or icons to fetch, and no address of any kind: it must draw with no internet.
        assertFalse(offline.containsKey("glyphs"))
        assertFalse(offline.containsKey("sprite"))
        assertFalse(json.contains("http"))
    }

    @Test
    fun `the offline background is the outdoor style's land color`() {
        val offline = Json.parseToJsonElement(MapStyles.offlineStyleJson()).jsonObject
        val offlineColor = offline.getValue("layers").jsonArray.single().jsonObject.getValue("paint").jsonObject.string("background-color")

        assertEquals(MapStyles.LAND_COLOR, offlineColor)
        assertEquals(MapStyles.LAND_COLOR, layer("background").getValue("paint").jsonObject.string("background-color"))
    }

    @Test
    fun `the outdoor style is a version 8 style whose layers all have unique ids and known sources`() {
        assertEquals(8, style.getValue("version").jsonPrimitive.int)
        assertEquals(layers.size, layers.map { it.string("id") }.toSet().size)
        layers.filter { it.containsKey("source") }.forEach { assertTrue(it.string("id"), sources.containsKey(it.string("source"))) }
        assertEquals("background", layers.first().string("type"))
    }

    @Test
    fun `the map data, fonts and icons come from OpenFreeMap and the hill shading from Mapterhorn, all over https`() {
        val openMapTiles = sources.getValue("openmaptiles").jsonObject
        assertEquals("vector", openMapTiles.string("type"))
        // The TileJSON, which brings OpenFreeMap's current tiles and its OSM / OpenMapTiles attribution.
        assertEquals("https://tiles.openfreemap.org/planet", openMapTiles.string("url"))
        assertEquals("https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf", style.string("glyphs"))
        assertEquals("https://tiles.openfreemap.org/sprites/ofm_f384/ofm", style.string("sprite"))
        assertEquals(setOf("openmaptiles", "mapterhorn"), sources.keys)

        val addresses = Regex("""[a-z]+://[^"'<>\s{\\]+""").findAll(assetJson).map { URI(it.value) }.toList()
        assertTrue(addresses.isNotEmpty())
        addresses.forEach { assertEquals(it.toString(), "https", it.scheme) }
        assertEquals(setOf("tiles.openfreemap.org", "tiles.mapterhorn.com", "mapterhorn.com"), addresses.map { it.host }.toSet())
    }

    @Test
    fun `hill shading is Mapterhorn's terrarium tiles, asked for only from zoom 5 to 12, with its attribution`() {
        val dem = sources.getValue("mapterhorn").jsonObject
        assertEquals("raster-dem", dem.string("type"))
        assertEquals("terrarium", dem.string("encoding"))
        assertEquals(listOf("https://tiles.mapterhorn.com/{z}/{x}/{y}.webp"), dem.getValue("tiles").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(512, dem.getValue("tileSize").jsonPrimitive.int)
        assertEquals(5, dem.getValue("minzoom").jsonPrimitive.int)
        assertEquals(12, dem.getValue("maxzoom").jsonPrimitive.int)
        assertEquals(5, layer("hillshade").getValue("minzoom").jsonPrimitive.int)
        val attribution = dem.string("attribution")
        assertTrue(attribution, attribution.contains("Mapterhorn") && attribution.contains("https://mapterhorn.com/attribution"))
    }

    @Test
    fun `the hill shading is under the water, roads, tracks, paths and every label`() {
        val hillshade = layer("hillshade")
        assertEquals("hillshade", hillshade.string("type"))
        assertEquals("mapterhorn", hillshade.string("source"))
        val firstOnTop = layers.indexOfFirst { it.string("type") == "symbol" || it.string("id") in setOf("water", "road", "road-casing", "track", "path") }
        assertTrue(indexOf("hillshade") < firstOnTop)
        assertTrue(indexOf("landcover-sand") < indexOf("hillshade"))
    }

    @Test
    fun `tracks are brown dashes, paths red dashes and wadis blue dashes`() {
        listOf("track", "path", "waterway-intermittent").forEach { id ->
            assertTrue(id, layer(id).getValue("paint").jsonObject.containsKey("line-dasharray"))
        }
        assertEquals("#8B5A2B", layer("track").getValue("paint").jsonObject.string("line-color"))
        assertEquals("#B83A2C", layer("path").getValue("paint").jsonObject.string("line-color"))
        assertTrue(layer("waterway-intermittent").getValue("filter").toString().contains("intermittent"))
        assertTrue(layer("track").getValue("filter").toString().contains("unpaved"))
    }

    @Test
    fun `peaks show their height`() {
        val peak = layer("peak")
        assertEquals("mountain_peak", peak.string("source-layer"))
        assertTrue(peak.getValue("layout").jsonObject.getValue("text-field").toString().contains("\"ele\""))
    }

    @Test
    fun `every label uses a font OpenFreeMap serves`() {
        val fonts = layers.mapNotNull { it["layout"]?.jsonObject?.get("text-font") }.flatMap { it.strings() }.toSet()
        assertEquals(setOf("Noto Sans Regular", "Noto Sans Bold", "Noto Sans Italic"), fonts)
    }

    @Test
    fun `in English the style is used as shipped`() {
        assertEquals(assetJson, MapStyles.outdoorStyleJson(assetJson, "en"))
    }

    @Test
    fun `in Hebrew every name label shows the Hebrew name, else the local one`() {
        val hebrew = Json.parseToJsonElement(MapStyles.outdoorStyleJson(assetJson, "he")).jsonObject
        val text = hebrew.toString()

        assertFalse(text.contains("name:en"))
        assertFalse(text.contains("name:latin"))
        val hebrewLabel = Json.parseToJsonElement("""["coalesce", ["get", "name:he"], ["get", "name"]]""")
        val labelled = hebrew.getValue("layers").jsonArray.map { it.jsonObject }
            .filter { it["layout"]?.jsonObject?.get("text-field")?.toString()?.contains("\"name\"") == true }
        assertTrue(labelled.size > 10)
        labelled.forEach { assertTrue(it.string("id"), it.getValue("layout").jsonObject.getValue("text-field").toString().contains(hebrewLabel.toString())) }
        // Nothing else changed.
        assertEquals(layers.map { it.string("id") }, hebrew.getValue("layers").jsonArray.map { it.jsonObject.string("id") })
        assertEquals(MapStyles.outdoorStyleJson(assetJson, "he"), MapStyles.outdoorStyleJson(assetJson, "iw"))
    }
}
