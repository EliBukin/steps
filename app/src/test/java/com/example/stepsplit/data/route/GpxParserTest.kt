package com.example.stepsplit.data.route

import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.route.RouteLine
import com.example.stepsplit.ui.trips.GpxExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [GpxParser] on files in the style of the apps people export routes from (all made up - see
 * `src/test/resources/gpx/import/README.md`), on the app's own GPX export, and on broken and
 * hostile files. Robolectric: the parser is Android's own pull parser, as on the phone.
 */
@RunWith(RobolectricTestRunner::class)
class GpxParserTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("gpx/import/$name")) { "Missing test file $name" }.use { it.readBytes() }

    private fun parsed(bytes: ByteArray): GpxParser.Result.Parsed {
        val result = GpxParser.parse(bytes)
        assertTrue("Expected a parsed file, got $result", result is GpxParser.Result.Parsed)
        return result as GpxParser.Result.Parsed
    }

    private fun parsed(text: String) = parsed(text.toByteArray())

    private fun RouteLine.latitudes() = segments.flatMap { it.latitudes.toList() }

    @Test
    fun `a Garmin-style file keeps both segments, heights and the waypoint, and skips the extensions`() {
        val gpx = parsed(resource("garmin-style.gpx"))

        assertNull(gpx.fileTitle)
        assertEquals("Morning walk", gpx.firstLineName)
        assertEquals(1, gpx.lines.size)
        val line = gpx.lines.single()
        assertEquals(listOf(3, 2), line.segments.map { it.size })
        assertEquals(30.510, line.segments[0].latitudes[0], 1e-9)
        assertEquals(34.915, line.segments[1].longitudes[1], 1e-9)
        assertEquals(634.5, line.segments[0].elevations[1], 1e-9)
        assertEquals("Water cache", gpx.waypoints.single().name)
        assertEquals(640.2, gpx.waypoints.single().elevationMeters!!, 1e-9)
        assertEquals(0, gpx.skippedPoints)
        assertFalse(gpx.damaged)
    }

    @Test
    fun `a Strava-style file reads its one track`() {
        val gpx = parsed(resource("strava-style.gpx"))

        assertEquals("Evening Ride", gpx.firstLineName)
        assertEquals(3, gpx.lines.single().pointCount)
        assertEquals(313.6, gpx.lines.single().segments.single().elevations[2], 1e-9)
        assertTrue(gpx.waypoints.isEmpty())
    }

    @Test
    fun `an OsmAnd-style file reads its route, track and waypoints with their notes`() {
        val gpx = parsed(resource("osmand-style.gpx"))

        assertEquals("Wadi plan", gpx.fileTitle)
        assertEquals("Drive in", gpx.firstLineName)
        // The route (rte) first, then the track, in file order.
        assertEquals(listOf(4, 2), gpx.lines.map { it.pointCount })
        assertEquals(listOf("Lookout", "Parking"), gpx.waypoints.map { it.name })
        assertEquals("Short climb on the left", gpx.waypoints[0].note)
        // No desc: the comment is the note.
        assertEquals("Gate closes at dusk", gpx.waypoints[1].note)
        assertNull(gpx.waypoints[0].elevationMeters)
    }

    @Test
    fun `a Wikiloc-style file reads CDATA names and keeps the description as the note`() {
        val gpx = parsed(resource("wikiloc-style.gpx"))

        assertEquals("Canyon loop & spring", gpx.fileTitle)
        assertEquals("Canyon loop", gpx.firstLineName)
        assertEquals(4, gpx.lines.single().pointCount)
        // Runs of spaces become one.
        assertEquals("Spring & pool", gpx.waypoints.single().name)
        assertEquals("<p>Cold water, take a hat</p>", gpx.waypoints.single().note)
    }

    @Test
    fun `a Komoot-style planned route reads its name and heights`() {
        val gpx = parsed(resource("komoot-style.gpx"))

        assertEquals("Ridge hike (planned)", gpx.fileTitle)
        assertEquals(listOf(500.1, 505.9, 512.3), gpx.lines.single().segments.single().elevations.toList())
    }

    @Test
    fun `GPX 1_0 with its name under gpx and an ISO-8859-1 encoding`() {
        val gpx = parsed(resource("gpx10-route.gpx"))

        assertEquals("Old route", gpx.fileTitle)
        assertEquals("Route A", gpx.firstLineName)
        assertEquals(3, gpx.lines.single().pointCount)
        assertEquals("Café", gpx.waypoints.single().name)
        // No heights at all.
        assertTrue(gpx.lines.single().segments.single().elevations.all { it.isNaN() })
    }

    @Test
    fun `a file without a namespace reads the same`() {
        val gpx = parsed(resource("no-namespace.gpx"))

        assertEquals(listOf(31.10, 31.11), gpx.lines.single().latitudes())
    }

    @Test
    fun `Hebrew names, written out or as character references`() {
        val gpx = parsed(resource("hebrew-names.gpx"))

        assertEquals("מסלול לדוגמה", gpx.fileTitle)
        assertEquals("יום ראשון", gpx.firstLineName)
        assertEquals("מעין", gpx.waypoints.single().name)
    }

    @Test
    fun `a UTF-16 file with a byte order mark`() {
        val text = """<?xml version="1.0" encoding="UTF-16"?><gpx version="1.1"><trk><name>שביל</name><trkseg>""" +
            """<trkpt lat="31.4" lon="35.4"/><trkpt lat="31.41" lon="35.41"/></trkseg></trk></gpx>"""
        val gpx = parsed(text.toByteArray(Charsets.UTF_16))

        assertEquals("שביל", gpx.firstLineName)
        assertEquals(2, gpx.lines.single().pointCount)
    }

    @Test
    fun `a UTF-8 byte order mark`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """<?xml version="1.0"?><gpx><trk><trkseg><trkpt lat="31.4" lon="35.4"/><trkpt lat="31.41" lon="35.41"/></trkseg></trk></gpx>""".toByteArray()

        assertEquals(2, parsed(bytes).lines.single().pointCount)
    }

    @Test
    fun `the app's own GPX export reads back point for point`() {
        val points = List(25) { i ->
            TripPoint(
                capturedAtEpochSecond = 1_800_000_000L + i * 5,
                latitude = 30.6 + i * 0.0003,
                longitude = 34.8 - i * 0.0002,
                accuracyMeters = 5f,
                altitudeMeters = if (i == 3) null else 700.0 + i,
                speedMetersPerSecond = 1.3f,
            )
        }
        val spots = listOf(GpxExport.Waypoint(30.601, 34.799, 702.0, 1_800_000_010L, "Spot 1 & <view>"))
        val gpx = parsed(GpxExport.toGpx(points, name = "Made-up walk", waypoints = spots))

        assertEquals("Made-up walk", gpx.fileTitle)
        val segment = gpx.lines.single().segments.single()
        assertEquals(points.size, segment.size)
        // The export writes 7 decimals: about a centimeter.
        points.forEachIndexed { i, point ->
            assertEquals(point.latitude, segment.latitudes[i], 1e-7)
            assertEquals(point.longitude, segment.longitudes[i], 1e-7)
            assertEquals(point.altitudeMeters ?: Double.NaN, segment.elevations[i], 1e-7)
        }
        assertEquals("Spot 1 & <view>", gpx.waypoints.single().name)
        assertEquals(702.0, gpx.waypoints.single().elevationMeters!!, 1e-9)
    }

    @Test
    fun `several tracks are separate lines, and the first named one names the file`() {
        val gpx = parsed(
            """<gpx><trk><trkseg><trkpt lat="31" lon="35"/><trkpt lat="31.1" lon="35"/></trkseg></trk>
            <trk><name>Day 2</name><trkseg><trkpt lat="31.1" lon="35"/><trkpt lat="31.2" lon="35"/></trkseg></trk>
            <trk><name>Day 3</name><trkseg><trkpt lat="31.2" lon="35"/><trkpt lat="31.3" lon="35"/></trkseg></trk></gpx>""",
        )

        assertEquals(3, gpx.lines.size)
        assertEquals("Day 2", gpx.firstLineName)
    }

    @Test
    fun `bad positions are skipped and counted, a bad height only loses the height`() {
        val gpx = parsed(
            """<gpx><trk><trkseg>
            <trkpt lat="31.0" lon="35.0"><ele>100</ele></trkpt>
            <trkpt lat="31,1" lon="35,1"/>
            <trkpt lat="NaN" lon="35.0"/>
            <trkpt lat="Infinity" lon="35.0"/>
            <trkpt lat="0x1p4" lon="35.0"/>
            <trkpt lat="91" lon="35.0"/>
            <trkpt lat="31.0" lon="181"/>
            <trkpt lat="0" lon="0"/>
            <trkpt lon="35.0"/>
            <trkpt lat="31.2" lon="35.2"><ele>high</ele></trkpt>
            <trkpt lat=" 3.12e1 " lon="+35.3"><ele>99999</ele></trkpt>
            </trkseg></trk></gpx>""",
        )

        val segment = gpx.lines.single().segments.single()
        assertEquals(listOf(31.0, 31.2, 31.2), segment.latitudes.toList())
        assertEquals(listOf(100.0, Double.NaN, Double.NaN), segment.elevations.toList())
        assertEquals(8, gpx.skippedPoints)
    }

    @Test
    fun `a lone point in a segment draws nothing and is left out`() {
        val gpx = parsed(
            """<gpx><trk><trkseg><trkpt lat="31" lon="35"/></trkseg>
            <trkseg><trkpt lat="31.1" lon="35"/><trkpt lat="31.2" lon="35"/></trkseg></trk>
            <trk><trkseg><trkpt lat="32" lon="35"/></trkseg></trk></gpx>""",
        )

        assertEquals(1, gpx.lines.size)
        assertEquals(1, gpx.lines.single().segments.size)
        assertEquals(2, gpx.skippedPoints)
    }

    @Test
    fun `a waypoints-only file has no lines`() {
        val gpx = parsed("""<gpx><wpt lat="31" lon="35"><name>A</name></wpt><wpt lat="32" lon="35"/></gpx>""")

        assertTrue(gpx.lines.isEmpty())
        assertEquals(listOf("A", null), gpx.waypoints.map { it.name })
    }

    @Test
    fun `a file cut off part way keeps what came before, and says it's damaged`() {
        val full = String(resource("garmin-style.gpx"))
        val cut = full.substring(0, full.indexOf("<trkpt lat=\"30.512000\""))
        val gpx = parsed(cut)

        assertTrue(gpx.damaged)
        assertEquals(2, gpx.lines.single().pointCount)
        assertEquals("Water cache", gpx.waypoints.single().name)
    }

    @Test
    fun `broken XML part way keeps what came before`() {
        val gpx = parsed("""<gpx><trk><trkseg><trkpt lat="31" lon="35"/><trkpt lat="31.1" lon="35"/><trkpt lat="31.2" <<< </trkseg></trk></gpx>""")

        assertTrue(gpx.damaged)
        assertEquals(2, gpx.lines.single().pointCount)
    }

    @Test
    fun `a DOCTYPE is refused - billion laughs`() {
        val text = """<?xml version="1.0"?>
            <!DOCTYPE gpx [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
              <!ENTITY d "&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;">
              <!ENTITY e "&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;">
              <!ENTITY f "&e;&e;&e;&e;&e;&e;&e;&e;&e;&e;">
            ]>
            <gpx><metadata><name>&f;</name></metadata><trk><trkseg><trkpt lat="31" lon="35"/><trkpt lat="31.1" lon="35"/></trkseg></trk></gpx>"""

        assertEquals(GpxParser.Result.Refused, GpxParser.parse(text.toByteArray()))
    }

    @Test
    fun `a DOCTYPE is refused - an external entity naming a local file`() {
        val text = """<?xml version="1.0"?>
            <!DOCTYPE gpx [ <!ENTITY secret SYSTEM "file:///etc/hosts"> ]>
            <gpx><metadata><name>&secret;</name></metadata></gpx>"""

        assertEquals(GpxParser.Result.Refused, GpxParser.parse(text.toByteArray()))
    }

    @Test
    fun `a DOCTYPE with an external DTD is refused`() {
        val text = """<?xml version="1.0"?><!DOCTYPE gpx SYSTEM "http://example.invalid/gpx.dtd"><gpx></gpx>"""

        assertEquals(GpxParser.Result.Refused, GpxParser.parse(text.toByteArray()))
    }

    @Test
    fun `an undeclared entity is dropped, the built-in ones and character references read`() {
        val gpx = parsed("""<gpx><wpt lat="31" lon="35"><name>A&amp;B&#x5D0;&oops;C</name></wpt></gpx>""")

        assertFalse(gpx.damaged)
        assertEquals("A&BאC", gpx.waypoints.single().name)
    }

    @Test
    fun `KML, HTML, other XML, empty files and plain text are not GPX`() {
        assertEquals(GpxParser.Result.Kml, GpxParser.parse("""<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document/></kml>""".toByteArray()))
        assertEquals(GpxParser.Result.Refused, GpxParser.parse("<!DOCTYPE html><html><body>Hi</body></html>".toByteArray()))
        assertEquals(GpxParser.Result.NotGpx, GpxParser.parse("<html><body>Hi</body></html>".toByteArray()))
        assertEquals(GpxParser.Result.NotGpx, GpxParser.parse("""<?xml version="1.0"?><svg/>""".toByteArray()))
        assertEquals(GpxParser.Result.NotGpx, GpxParser.parse(ByteArray(0)))
        assertEquals(GpxParser.Result.NotGpx, GpxParser.parse("just some text".toByteArray()))
        assertEquals(GpxParser.Result.NotGpx, GpxParser.parse(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x7F)))
    }

    @Test
    fun `waypoints past the limit are left out and counted`() {
        val many = (0 until GpxParser.MAX_WAYPOINTS + 5).joinToString("") { """<wpt lat="31" lon="${35 + it * 0.0001}"/>""" }
        val gpx = parsed("<gpx>$many</gpx>")

        assertEquals(GpxParser.MAX_WAYPOINTS, gpx.waypoints.size)
        assertEquals(5, gpx.skippedWaypoints)
    }

    @Test
    fun `very long names are cut`() {
        val gpx = parsed("""<gpx><metadata><name>${"x".repeat(10_000)}</name></metadata><wpt lat="31" lon="35"><desc>${"y".repeat(10_000)}</desc></wpt></gpx>""")

        assertEquals(200, gpx.fileTitle!!.length)
        assertEquals(500, gpx.waypoints.single().note!!.length)
    }
}
