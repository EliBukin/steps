package com.example.stepsplit.ui.trips

import com.example.stepsplit.domain.model.TripPoint
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class GpxExportTest {

    private fun point(
        epochSecond: Long,
        lat: Double,
        lon: Double,
        altitude: Double? = null,
    ) = TripPoint(
        capturedAtEpochSecond = epochSecond,
        latitude = lat,
        longitude = lon,
        accuracyMeters = 5f,
        altitudeMeters = altitude,
        speedMetersPerSecond = null,
    )

    private fun parse(gpx: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))
            .documentElement

    @Test
    fun `an empty point list still produces valid, well-formed GPX with no track points`() {
        val gpx = GpxExport.toGpx(emptyList())
        val root = parse(gpx)
        assertEquals("gpx", root.tagName)
        val trkpts = root.getElementsByTagName("trkpt")
        assertEquals(0, trkpts.length)
    }

    @Test
    fun `point order is preserved exactly as given`() {
        val points = listOf(
            point(1_000L, 10.0, 20.0),
            point(1_010L, 10.001, 20.001),
            point(1_020L, 10.002, 20.002),
        )
        val root = parse(GpxExport.toGpx(points))
        val trkpts = root.getElementsByTagName("trkpt")
        assertEquals(3, trkpts.length)
        for (i in points.indices) {
            val element = trkpts.item(i) as Element
            assertEquals(points[i].latitude, element.getAttribute("lat").toDouble(), 1e-9)
            assertEquals(points[i].longitude, element.getAttribute("lon").toDouble(), 1e-9)
        }
    }

    @Test
    fun `timestamps are UTC ISO-8601 instants`() {
        val gpx = GpxExport.toGpx(listOf(point(1_755_000_000L, 1.0, 2.0)))
        val root = parse(gpx)
        val time = (root.getElementsByTagName("trkpt").item(0) as Element)
            .getElementsByTagName("time").item(0).textContent
        assertEquals(java.time.Instant.ofEpochSecond(1_755_000_000L).toString(), time)
        assertTrue("expected a trailing Z for UTC, got $time", time.endsWith("Z"))
    }

    @Test
    fun `elevation is included only when altitude is present`() {
        val withAltitude = point(1_000L, 1.0, 2.0, altitude = 123.4)
        val withoutAltitude = point(1_010L, 1.0, 2.0, altitude = null)
        val root = parse(GpxExport.toGpx(listOf(withAltitude, withoutAltitude)))
        val trkpts = root.getElementsByTagName("trkpt")

        val firstEle = (trkpts.item(0) as Element).getElementsByTagName("ele")
        assertEquals(1, firstEle.length)
        assertEquals(123.4, firstEle.item(0).textContent.toDouble(), 1e-6)

        val secondEle = (trkpts.item(1) as Element).getElementsByTagName("ele")
        assertEquals(0, secondEle.length)
    }

    @Test
    fun `decimal formatting is locale-independent even under a comma-decimal default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val gpx = GpxExport.toGpx(listOf(point(1_000L, 12.5, -7.25)))
            val root = parse(gpx)
            val element = root.getElementsByTagName("trkpt").item(0) as Element
            // A comma-decimal locale leaking through would produce "12,5000000", which is not
            // parseable as the Double it's supposed to represent, and not valid GPX either.
            assertEquals(12.5, element.getAttribute("lat").toDouble(), 1e-6)
            assertEquals(-7.25, element.getAttribute("lon").toDouble(), 1e-6)
            assertFalse(element.getAttribute("lat").contains(","))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `XML special characters are escaped`() {
        val escaped = GpxExport.escapeXml("Tom & Jerry's \"great\" <hike>")
        assertEquals("Tom &amp; Jerry&apos;s &quot;great&quot; &lt;hike&gt;", escaped)
        // Confirm the escaped text is itself well-formed inside an XML attribute.
        val root = parse("<root attr=\"$escaped\"/>")
        assertEquals("Tom & Jerry's \"great\" <hike>", root.getAttribute("attr"))
    }

    @Test
    fun `the document declares UTF-8 encoding`() {
        assertTrue(GpxExport.toGpx(emptyList()).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""))
    }

    @Test
    fun `the GPX version is 1_1 with the correct namespace`() {
        val root = parse(GpxExport.toGpx(emptyList()))
        assertEquals("1.1", root.getAttribute("version"))
        assertEquals("http://www.topografix.com/GPX/1/1", root.getAttribute("xmlns"))
    }
}
