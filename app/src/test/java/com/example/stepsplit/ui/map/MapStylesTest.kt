package com.example.stepsplit.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStylesTest {

    @Test
    fun `the offline background is a valid style with one background layer and nothing to download`() {
        listOf(false, true).forEach { isDark ->
            val json = MapStyles.offlineStyleJson(isDark)
            val style = Json.parseToJsonElement(json).jsonObject

            assertEquals(8, style.getValue("version").jsonPrimitive.int)
            assertTrue(style.getValue("sources").jsonObject.isEmpty())
            val layers = style.getValue("layers").jsonArray
            assertEquals(1, layers.size)
            assertEquals("background", layers.single().jsonObject.getValue("type").jsonPrimitive.content)
            // No fonts or icons to fetch, and no address of any kind: it must draw with no internet.
            assertFalse(style.containsKey("glyphs"))
            assertFalse(style.containsKey("sprite"))
            assertFalse(json.contains("http"))
        }
    }

    @Test
    fun `the offline background follows light and dark`() {
        assertNotEquals(MapStyles.offlineStyleJson(isDark = false), MapStyles.offlineStyleJson(isDark = true))
    }

    @Test
    fun `the online map comes from OpenFreeMap over https, light or dark`() {
        assertEquals("https://tiles.openfreemap.org/styles/positron", MapStyles.styleUrl(isDark = false))
        assertEquals("https://tiles.openfreemap.org/styles/dark", MapStyles.styleUrl(isDark = true))
    }
}
