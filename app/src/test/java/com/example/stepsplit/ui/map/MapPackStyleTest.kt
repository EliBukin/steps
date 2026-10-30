package com.example.stepsplit.ui.map

import com.example.stepsplit.data.mappack.InstalledMapPack
import com.example.stepsplit.data.mappack.MapPackArea
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPackStyleTest {

    private val assetJson = File("src/main/assets/${MapStyles.OUTDOOR_STYLE_ASSET}").readText()
    private val pack = InstalledMapPack(
        regionId = "israel",
        version = "2026-10-29",
        vectorFile = File("/data/maps/israel/2026-10-29/israel-2026-10-29-vector.pmtiles"),
        demFile = File("/data/maps/israel/2026-10-29/israel-2026-10-29-dem.pmtiles"),
        area = MapPackArea(listOf(doubleArrayOf(34.5, 31.0, 35.5, 31.0, 35.5, 32.0, 34.5, 32.0))),
        bytes = 151_000_000,
    )
    private val online = Json.parseToJsonElement(assetJson).jsonObject
    private val style = Json.parseToJsonElement(MapPackStyle.styleJson(assetJson, pack)).jsonObject
    private val layers = style.getValue("layers").jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun layer(id: String) = layers.single { it.string("id") == id }
    private fun indexOf(id: String) = layers.indexOfFirst { it.string("id") == id }

    private fun JsonElement.strings(): List<String> = when (this) {
        is JsonPrimitive -> if (isString) listOf(content) else emptyList()
        is JsonArray -> flatMap { it.strings() }
        is JsonObject -> values.flatMap { it.strings() }
    }

    @Test
    fun `nothing in the pack style goes to the network`() {
        val addresses = style.strings().filter { "://" in it }
        assertTrue(addresses.isNotEmpty())
        addresses.forEach { assertFalse("network address in the pack style: $it", it.startsWith("http")) }
        assertEquals(
            "pmtiles://file:///data/maps/israel/2026-10-29/israel-2026-10-29-vector.pmtiles",
            style.getValue("sources").jsonObject.getValue("openmaptiles").jsonObject.string("url"),
        )
        val dem = style.getValue("sources").jsonObject.getValue("mapterhorn").jsonObject
        assertEquals("pmtiles://file:///data/maps/israel/2026-10-29/israel-2026-10-29-dem.pmtiles", dem.string("url"))
        assertEquals("terrarium", dem.string("encoding"))
    }

    @Test
    fun `the fonts and icons the style asks for are in the app`() {
        assertEquals(MapPackStyle.GLYPHS_URL, style.string("glyphs"))
        val fonts = layers.mapNotNull { it["layout"]?.jsonObject?.get("text-font") }.flatMap { it.strings() }.toSet()
        assertTrue(fonts.isNotEmpty())
        for (font in fonts) {
            // Every range is there (most of them empty): a range MapLibre can't read hides every label around it.
            for (start in 0 until 65536 step 256) {
                assertTrue("$font $start", File("src/main/assets/map/glyphs/$font/$start-${start + 255}.pbf").isFile)
            }
            for (range in listOf("0-255", "1280-1535", "1536-1791", "7680-7935")) { // Latin, Hebrew, Arabic, Latin with dots (Ḥ)
                assertTrue("$font $range has glyphs", File("src/main/assets/map/glyphs/$font/$range.pbf").length() > 10_000)
            }
        }
        for (file in listOf("ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png")) {
            assertTrue(file, File("src/main/assets/map/sprite/$file").isFile)
        }
        assertTrue("the fonts' license ships with them", File("src/main/assets/map/glyphs/OFL.txt").isFile)
    }

    @Test
    fun `every layer of the online style is still there, in the same order`() {
        val onlineIds = online.getValue("layers").jsonArray.map { it.jsonObject.string("id") }
        val packIds = layers.map { it.string("id") }.filterNot { it.startsWith("pack-") }
        assertEquals(onlineIds, packIds)
    }

    @Test
    fun `tracks, paths, minor roads and wadis show from far out, then hand over to the base map`() {
        val expected = mapOf(
            "pack-track" to (10 to 14),
            "pack-path" to (11 to 14),
            "pack-road-tertiary" to (9 to 11),
            "pack-road-minor" to (10 to 12),
            "pack-waterway-stream" to (10 to 13),
            "pack-waterway-intermittent" to (9 to 13),
        )
        for ((id, zooms) in expected) {
            val l = layer(id)
            assertEquals(id, zooms.first.toDouble(), l.getValue("minzoom").jsonPrimitive.double, 0.0)
            assertEquals(id, zooms.second.toDouble(), l.getValue("maxzoom").jsonPrimitive.double, 0.0)
            assertTrue(id, l.string("source-layer").startsWith("outdoor_"))
        }
        // A copy draws like the layer it copies.
        assertEquals(layer("track").getValue("paint"), layer("pack-track").getValue("paint"))
        assertEquals(indexOf("track-paved") + 1, indexOf("pack-track"))
        assertTrue(indexOf("pack-road-tertiary") < indexOf("road"))
    }

    @Test
    fun `trails go over the tracks they follow, contours right over the hill shading`() {
        assertTrue(indexOf("pack-trail") > indexOf("pack-track"))
        assertTrue(indexOf("pack-trail") < indexOf("road-casing"))
        assertEquals("trail", layer("pack-trail").string("source-layer"))
        assertEquals(indexOf("hillshade") + 1, indexOf("pack-contour"))
        assertEquals("contour", layer("pack-contour-label").string("source-layer"))
    }

    @Test
    fun `labels still follow the app's language`() {
        val hebrew = MapStyles.outdoorStyleJson(MapPackStyle.styleJson(assetJson, pack), "he")
        assertTrue(hebrew.contains("name:he"))
        assertTrue(hebrew.contains("pmtiles://file://"))
    }

    @Test
    fun `the pack is used inside its area from zoom 7, and at any zoom once the online map failed`() {
        val packs = listOf(pack)
        assertEquals(pack, MapPackChoice.packFor(packs, 35.21, 31.77, 12.0, onlineFailed = false))
        assertNull(MapPackChoice.packFor(packs, 35.21, 31.77, 5.0, onlineFailed = false))
        assertNotNull(MapPackChoice.packFor(packs, 35.21, 31.77, 5.0, onlineFailed = true))
        assertNull(MapPackChoice.packFor(packs, 35.93, 31.95, 12.0, onlineFailed = false))
        assertNull(MapPackChoice.packFor(emptyList(), 35.21, 31.77, 12.0, onlineFailed = true))
    }

    @Test
    fun `the Map tab offers the Israel map around Israel only`() {
        assertTrue(OfflineMapOffer.isNear(longitude = 34.78, latitude = 32.08)) // Tel Aviv
        assertTrue(OfflineMapOffer.isNear(longitude = 34.95, latitude = 29.55)) // Eilat
        assertFalse(OfflineMapOffer.isNear(longitude = 2.35, latitude = 48.86)) // Paris
    }
}
