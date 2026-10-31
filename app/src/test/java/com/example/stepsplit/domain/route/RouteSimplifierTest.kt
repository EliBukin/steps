package com.example.stepsplit.domain.route

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouteSimplifierTest {

    @Test
    fun `a straight line keeps only its ends`() {
        val line = RouteTestShapes.north(1_000.0)

        assertArrayEquals(intArrayOf(0, line.size - 1), RouteSimplifier.keptIndices(line, 2.0))
    }

    @Test
    fun `a corner further off than the tolerance stays, a smaller wobble goes`() {
        val segment = RouteSegment(
            doubleArrayOf(31.0, 31.0005, 31.001, 31.0015, 31.002),
            doubleArrayOf(35.0, 35.00001, 35.0, 35.001, 35.0),
            DoubleArray(5) { Double.NaN },
        )

        // The 1 m wobble at index 1 goes; the 95 m corner at index 3 stays.
        assertArrayEquals(intArrayOf(0, 2, 3, 4), RouteSimplifier.keptIndices(segment, 2.0))
    }

    @Test
    fun `a short line is drawn as it is`() {
        val line = RouteLine(listOf(RouteTestShapes.circle(500.0, 200)))

        assertSame(line, RouteSimplifier.forDrawing(line))
    }

    @Test
    fun `a long wiggly line is drawn with at most the drawing limit, ends kept`() {
        val circle = RouteTestShapes.circle(3_000.0, 40_000)
        val drawn = RouteSimplifier.forDrawing(RouteLine(listOf(circle)))

        val segment = drawn.segments.single()
        assertTrue(segment.size <= RouteSimplifier.MAX_DRAWN_POINTS_PER_LINE)
        assertTrue(segment.size > 50)
        assertEquals(circle.latitudes.first(), segment.latitudes.first(), 0.0)
        assertEquals(circle.latitudes.last(), segment.latitudes.last(), 0.0)
    }

    @Test
    fun `thinning to the import limit keeps the shape and every segment's ends`() {
        val lines = listOf(RouteLine(listOf(RouteTestShapes.circle(2_000.0, 30_000), RouteTestShapes.circle(1_000.0, 30_000))))

        val fitted = RouteSimplifier.fitToLimit(lines, maxPoints = 5_000)

        assertTrue(fitted.sumOf { it.pointCount } <= 5_000)
        assertEquals(2, fitted.single().segments.size)
        // Still the same length to within a percent.
        assertEquals(RouteMeasure.lengthMeters(lines.single()), RouteMeasure.lengthMeters(fitted.single()), RouteMeasure.lengthMeters(lines.single()) * 0.01)
    }

    @Test
    fun `lines that already fit are kept as they are`() {
        val lines = listOf(RouteLine(listOf(RouteTestShapes.north(100.0))))

        assertSame(lines, RouteSimplifier.fitToLimit(lines, maxPoints = 100))
    }

    @Test
    fun `thousands of two-point segments past the limit are cut off`() {
        val segments = List(3_000) { i ->
            RouteSegment(doubleArrayOf(31.0 + i * 0.001, 31.0 + i * 0.001 + 0.0005), doubleArrayOf(35.0, 35.0), doubleArrayOf(Double.NaN, Double.NaN))
        }

        val fitted = RouteSimplifier.fitToLimit(listOf(RouteLine(segments)), maxPoints = 1_000)

        assertEquals(500, fitted.single().segments.size)
    }

    /** A perfectly regular zigzag: every other point [amplitudeMeters] off the line, all equally far. */
    private fun zigzag(n: Int, amplitudeMeters: Double = 50.0) = RouteSegment(
        DoubleArray(n) { RouteTestShapes.LAT0 + (if (it % 2 == 0) 0.0 else amplitudeMeters) / RouteTestShapes.METERS_PER_DEGREE },
        DoubleArray(n) { RouteTestShapes.LON0 + it * 1e-4 },
        DoubleArray(n) { Double.NaN },
    )

    @Test
    fun `a regular zigzag takes time in proportion to its points, not their square`() {
        // Before, 40,000 points took about 3.7 s on a desktop and 200,000 would take about 90 s.
        val segment = zigzag(200_001)
        val start = System.nanoTime()
        val kept = RouteSimplifier.keptIndices(segment, 2.0)
        val millis = (System.nanoTime() - start) / 1_000_000

        assertEquals("every corner is further off than the tolerance", segment.size, kept.size)
        assertTrue("took $millis ms", millis < 5_000)
        assertArrayEquals(intArrayOf(0, segment.size - 1), RouteSimplifier.keptIndices(segment, 64.0))
    }

    @Test
    fun `a file of a million points is thinned evenly to twice the limit first`() {
        val million = RouteLine(listOf(RouteTestShapes.circle(5_000.0, 999_999)))

        val thinned = RouteSimplifier.evenly(listOf(million), 200_000).single().segments.single()

        assertTrue("${thinned.size} points", thinned.size in 190_000..200_001)
        assertEquals(million.segments.single().latitudes.last(), thinned.latitudes.last(), 0.0)
        assertSame("already under it: as it is", million, RouteSimplifier.evenly(listOf(million), 1_000_000).single())
    }

    @Test
    fun `simplifying stops when the work is cancelled`() {
        var checks = 0
        try {
            RouteSimplifier.fitToLimit(listOf(RouteLine(listOf(RouteTestShapes.circle(3_000.0, 40_000)))), maxPoints = 1_000) {
                if (++checks > 10) throw CancellationException("left the screen")
            }
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals(11, checks)
    }

    @Test
    fun `distance to a segment, not to its endless line`() {
        assertEquals(3.0, RouteSimplifier.distanceToSegment(0.0, 3.0, -1.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(5.0, RouteSimplifier.distanceToSegment(4.0, 3.0, -1.0, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(5.0, RouteSimplifier.distanceToSegment(3.0, 4.0, 0.0, 0.0, 0.0, 0.0), 1e-9)
    }
}
