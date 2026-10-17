package com.example.stepsplit.ui.trips

import com.example.stepsplit.domain.model.TripPoint
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
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

    /** Arabic and Persian locales format with their own digits (٣١٫٧٧ / ۳۱٫۷۷); GPX must still get ASCII digits and a dot everywhere. */
    @Test
    fun `numbers and times stay ASCII under locales with their own digits`() {
        val original = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "he-IL")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val gpx = GpxExport.toGpx(
                    listOf(point(1_000L, 31.77, 35.21, altitude = -410.5)),
                    waypoints = listOf(GpxExport.Waypoint(31.78, 35.22, 812.25, 1_060L, "Spot 1")),
                )
                assertTrue("$tag: $gpx", gpx.all { it.code < 128 })
                val trkpt = parse(gpx).getElementsByTagName("trkpt").item(0) as Element
                assertEquals(31.77, trkpt.getAttribute("lat").toDouble(), 1e-6)
                assertEquals(-410.5, trkpt.getElementsByTagName("ele").item(0).textContent.toDouble(), 1e-6)
                assertEquals("1970-01-01T00:16:40Z", trkpt.getElementsByTagName("time").item(0).textContent)
            }
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

    @Test
    fun `a trip name goes into both the file's and the track's name`() {
        val root = parse(GpxExport.toGpx(listOf(point(1_000L, 31.8, 35.4)), name = "Wadi Qelt hike"))
        val metadata = root.getElementsByTagName("metadata").item(0) as Element
        val track = root.getElementsByTagName("trk").item(0) as Element
        assertEquals("Wadi Qelt hike", metadata.getElementsByTagName("name").item(0).textContent)
        assertEquals("Wadi Qelt hike", (track.firstChildElement()).textContent)
        assertEquals("name", track.firstChildElement().tagName)
        assertEquals("metadata comes first, as GPX 1.1 requires", "metadata", root.firstChildElement().tagName)
    }

    @Test
    fun `a trip name is XML-escaped and keeps Hebrew as UTF-8`() {
        val name = "Tom & Jerry's <\"hike\"> בוואדי קלט"
        val gpx = GpxExport.toGpx(emptyList(), name = name)
        val names = parse(gpx).getElementsByTagName("name")
        assertEquals(2, names.length)
        assertEquals(name, names.item(0).textContent)
        assertEquals(name, names.item(1).textContent)
        assertFalse(gpx.contains("<hike>"))
    }

    @Test
    fun `no name, or a blank one, adds no metadata and no name`() {
        for (name in listOf(null, "", "  ")) {
            val root = parse(GpxExport.toGpx(listOf(point(1_000L, 31.8, 35.4)), name = name))
            assertEquals(0, root.getElementsByTagName("metadata").length)
            assertEquals(0, root.getElementsByTagName("name").length)
        }
    }

    private fun assertValidGpx11(gpx: String) {
        val xsd = checkNotNull(javaClass.classLoader?.getResource("gpx/gpx-1.1.xsd")) { "the GPX 1.1 schema test resource" }
        val schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd)
        schema.newValidator().validate(StreamSource(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8))))
    }

    private val spots = listOf(
        GpxExport.Waypoint(31.7701, 35.2101, 812.4, 1_005L, "Spot 1"),
        GpxExport.Waypoint(31.7702, 35.2102, null, 1_015L, "Ein & <Fawwar> \"spring\" מעיין"),
    )

    @Test
    fun `spots become waypoints after metadata and before the track`() {
        val gpx = GpxExport.toGpx(listOf(point(1_000L, 31.77, 35.21, 800.0)), name = "Sataf", waypoints = spots)
        val root = parse(gpx)
        val order = buildList {
            var node = root.firstChild
            while (node != null) {
                if (node is Element) add(node.tagName)
                node = node.nextSibling
            }
        }
        assertEquals(listOf("metadata", "wpt", "wpt", "trk"), order)
        val first = root.getElementsByTagName("wpt").item(0) as Element
        assertEquals(31.7701, first.getAttribute("lat").toDouble(), 1e-9)
        assertEquals(35.2101, first.getAttribute("lon").toDouble(), 1e-9)
        val children = buildList {
            var node = first.firstChild
            while (node != null) {
                if (node is Element) add(node.tagName to node.textContent)
                node = node.nextSibling
            }
        }
        assertEquals(listOf("ele" to "812.4000000", "time" to "1970-01-01T00:16:45Z", "name" to "Spot 1"), children)
        val second = root.getElementsByTagName("wpt").item(1) as Element
        assertEquals(0, second.getElementsByTagName("ele").length)
        assertEquals(spots[1].name, second.getElementsByTagName("name").item(0).textContent)
        assertFalse(gpx.contains("<Fawwar>"))
    }

    @Test
    fun `the file validates against the GPX 1_1 schema with and without spots`() {
        val points = listOf(point(1_000L, 31.77, 35.21, 800.0), point(1_010L, 31.771, 35.211))
        assertValidGpx11(GpxExport.toGpx(emptyList()))
        assertValidGpx11(GpxExport.toGpx(points))
        assertValidGpx11(GpxExport.toGpx(points, name = "Sataf"))
        assertValidGpx11(GpxExport.toGpx(points, name = "Sataf", waypoints = spots))
        assertValidGpx11(GpxExport.toGpx(points, waypoints = spots))
    }

    private fun Element.firstChildElement(): Element {
        var node = firstChild
        while (node != null && node !is Element) node = node.nextSibling
        return node as Element
    }
}
