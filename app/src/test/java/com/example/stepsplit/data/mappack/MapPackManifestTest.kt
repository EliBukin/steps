package com.example.stepsplit.data.mappack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPackManifestTest {

    @Test
    fun `reads the regions and finds each file next to the manifest`() {
        val manifest = MapPackManifest.parse(MapPackTestData.manifestJson(), MapPackTestData.MANIFEST_URL)!!
        val region = manifest.regions.single()

        assertEquals(1, manifest.schema)
        assertEquals("israel", region.id)
        assertEquals("2026-10-29", region.version)
        assertTrue(region.isComplete)
        assertEquals(
            "https://example.org/releases/download/maps/israel-2026-10-29-vector.pmtiles",
            region.files.single { it.role == MapPackFile.ROLE_VECTOR }.url,
        )
        assertEquals((MapPackTestData.vectorBytes.size + MapPackTestData.demBytes.size).toLong(), region.totalBytes)
    }

    @Test
    fun `a file name with a folder in it makes the whole manifest unreadable`() {
        val json = MapPackTestData.manifestJson().replace("israel-2026-10-29-dem.pmtiles", "../../shared_prefs/x.xml")
        assertNull(MapPackManifest.parse(json, MapPackTestData.MANIFEST_URL))
    }

    @Test
    fun `a region id or version that isn't a plain name makes the whole manifest unreadable`() {
        // Both are folder names on the phone, and folders are deleted with everything in them.
        for (id in listOf("..", "../x", ".hidden", "", "a/b")) {
            val json = MapPackTestData.manifestJson().replace("\"id\": \"israel\"", "\"id\": \"$id\"")
            assertNull("id '$id'", MapPackManifest.parse(json, MapPackTestData.MANIFEST_URL))
        }
        for (version in listOf("../../..", "", "2026 10 29", "v#1")) {
            assertNull("version '$version'", MapPackManifest.parse(MapPackTestData.manifestJson(version = version), MapPackTestData.MANIFEST_URL))
        }
        assertEquals("2026-10-29c", MapPackManifest.parse(MapPackTestData.manifestJson(version = "2026-10-29c"), MapPackTestData.MANIFEST_URL)!!.regions.single().version)
    }

    @Test
    fun `text that isn't a manifest is unreadable, not a crash`() {
        assertNull(MapPackManifest.parse("<html>404</html>", MapPackTestData.MANIFEST_URL))
        assertNull(MapPackManifest.parse("""{"schema": 1}""", MapPackTestData.MANIFEST_URL))
    }

    @Test
    fun `a region without both files isn't complete`() {
        val json = MapPackTestData.manifestJson().replace("\"role\": \"dem\"", "\"role\": \"extra\"")
        assertFalse(MapPackManifest.parse(json, MapPackTestData.MANIFEST_URL)!!.regions.single().isComplete)
    }

    @Test
    fun `the area has what's inside its outline, not what's around it`() {
        val area = MapPackManifest.parse(MapPackTestData.manifestJson(), MapPackTestData.MANIFEST_URL)!!.regions.single().area

        assertTrue(area.contains(longitude = 35.21, latitude = 31.77)) // Jerusalem
        assertFalse(area.contains(longitude = 35.93, latitude = 31.95)) // Amman
        assertFalse(area.contains(longitude = 35.0, latitude = 33.0)) // north of the square
    }

    @Test
    fun `a ring inside another is a hole, and the area survives a round trip through JSON`() {
        val outer = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
        val hole = doubleArrayOf(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0)
        val area = MapPackArea.fromJson(MapPackArea(listOf(outer, hole)).toJson())

        assertTrue(area.contains(2.0, 2.0))
        assertFalse(area.contains(5.0, 5.0))
        assertFalse(area.contains(12.0, 5.0))
    }
}
