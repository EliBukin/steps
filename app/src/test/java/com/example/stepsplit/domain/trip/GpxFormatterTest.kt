package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxFormatterTest {

    @Test
    fun `points are emitted in chronological order regardless of input order`() {
        val out = GpxFormatter.format(
            "trip",
            listOf(
                GpxPoint(latitude = 2.0, longitude = 2.0, elevationMeters = null, timeEpochSecond = 200L),
                GpxPoint(latitude = 1.0, longitude = 1.0, elevationMeters = null, timeEpochSecond = 100L),
                GpxPoint(latitude = 3.0, longitude = 3.0, elevationMeters = null, timeEpochSecond = 300L),
            ),
        )
        val firstIndex = out.indexOf("lat=\"1.0000000\"")
        val secondIndex = out.indexOf("lat=\"2.0000000\"")
        val thirdIndex = out.indexOf("lat=\"3.0000000\"")
        assertTrue(firstIndex in 0..<secondIndex)
        assertTrue(secondIndex in 0..<thirdIndex)
    }

    @Test
    fun `timestamps are formatted as UTC`() {
        val out = GpxFormatter.format("trip", listOf(GpxPoint(1.0, 1.0, null, 0L)))
        assertTrue(out.contains("<time>1970-01-01T00:00:00Z</time>"))
    }

    @Test
    fun `elevation is included only when supplied`() {
        val withElevation = GpxFormatter.format("trip", listOf(GpxPoint(1.0, 1.0, 123.4, 0L)))
        assertTrue(withElevation.contains("<ele>123.4</ele>"))

        val withoutElevation = GpxFormatter.format("trip", listOf(GpxPoint(1.0, 1.0, null, 0L)))
        assertFalse(withoutElevation.contains("<ele>"))
    }

    @Test
    fun `special characters in the track name are XML-escaped`() {
        val out = GpxFormatter.format("Trip <A> & \"B's\"", emptyList())
        assertTrue(out.contains("<name>Trip &lt;A&gt; &amp; &quot;B&apos;s&quot;</name>"))
        assertFalse(out.contains("<A>"))
    }

    @Test
    fun `an empty route produces a valid document with no track points`() {
        val out = GpxFormatter.format("empty trip", emptyList())
        assertTrue(out.contains("<trkseg>"))
        assertTrue(out.contains("</trkseg>"))
        assertFalse(out.contains("<trkpt"))
    }

    @Test
    fun `a single-point route produces exactly one track point`() {
        val out = GpxFormatter.format("one point", listOf(GpxPoint(1.0, 2.0, null, 0L)))
        assertEquals(1, Regex("<trkpt").findAll(out).count())
    }

    @Test
    fun `coordinates never use scientific notation for small magnitudes`() {
        val out = GpxFormatter.format("near origin", listOf(GpxPoint(0.00005, -0.00005, null, 0L)))
        assertFalse(out.contains("E-"))
        assertTrue(out.contains("lat=\"0.0000500\""))
    }

    @Test
    fun `output is deterministic for the same input`() {
        val points = listOf(GpxPoint(1.0, 2.0, 10.0, 100L), GpxPoint(1.1, 2.1, null, 200L))
        assertEquals(GpxFormatter.format("trip", points), GpxFormatter.format("trip", points))
    }
}
