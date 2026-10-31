package com.example.stepsplit.domain.route

import com.example.stepsplit.domain.route.RouteTestShapes.eastOf
import com.example.stepsplit.domain.route.RouteTestShapes.northOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteFollowerTest {

    private fun follower(vararg segments: RouteSegment) = RouteFollower(listOf(RouteLine(segments.toList())))

    @Test
    fun `on a straight route, done and left add up to its length`() {
        val follower = follower(RouteTestShapes.north(2_000.0))

        val progress = follower.progress(northOf(600.0), eastOf(15.0), accuracyMeters = 5f) as RouteFollower.Progress.OnRoute

        assertEquals(600.0, progress.doneMeters, 2.0)
        assertEquals(1_400.0, progress.leftMeters, 2.0)
        assertEquals(15.0, progress.offMeters, 1.0)
    }

    @Test
    fun `off the route, how far off it and how much is left from the closest place`() {
        val follower = follower(RouteTestShapes.north(2_000.0))

        val progress = follower.progress(northOf(1_000.0), eastOf(300.0), accuracyMeters = 5f) as RouteFollower.Progress.OffRoute

        assertEquals(300.0, progress.offMeters, 2.0)
        assertEquals(1_000.0, progress.leftMeters, 2.0)
    }

    @Test
    fun `a fix with a wide circle counts as on the route a bit further out`() {
        val follower = follower(RouteTestShapes.north(2_000.0))

        assertTrue(follower.progress(northOf(500.0), eastOf(80.0), accuracyMeters = 5f) is RouteFollower.Progress.OffRoute)
        assertTrue(follower.progress(northOf(500.0), eastOf(80.0), accuracyMeters = 100f) is RouteFollower.Progress.OnRoute)
        // But never further than MAX_ON_ROUTE_METERS.
        assertTrue(follower.progress(northOf(500.0), eastOf(300.0), accuracyMeters = 2_000f) is RouteFollower.Progress.OffRoute)
    }

    @Test
    fun `far from the route, how far its start is in a straight line`() {
        val follower = follower(RouteTestShapes.north(2_000.0))

        val progress = follower.progress(northOf(-5_000.0), eastOf(0.0), accuracyMeters = 5f) as RouteFollower.Progress.Far

        assertEquals(5_000.0, progress.toStartMeters, 10.0)
    }

    @Test
    fun `at the start of a loop you're at its start, and at the end its end`() {
        val loop = RouteTestShapes.circle(1_000.0, 720)
        val length = RouteMeasure.lengthMeters(RouteLine(listOf(loop)))
        val follower = follower(loop)

        // At the start (the east point), where the loop also ends.
        val start = follower.locate(loop.latitudes[0], loop.longitudes[0])
        assertEquals(0.0, start.alongMeters, 1.0)

        // Walk it round: each match follows on from the last.
        for (i in listOf(90, 180, 270, 360, 450, 540, 630, 700, 719)) {
            val match = follower.locate(loop.latitudes[i], loop.longitudes[i])
            assertEquals(length * i / 720, match.alongMeters, 15.0)
        }
        // Back where it started: now that's the end.
        assertEquals(length, follower.locate(loop.latitudes[0], loop.longitudes[0]).alongMeters, 15.0)
    }

    @Test
    fun `out and back on one trail stays on the leg you're on`() {
        val follower = follower(RouteTestShapes.outAndBack(1_000.0))

        // Out: the first fixes match the way out.
        assertEquals(300.0, follower.locate(northOf(300.0), eastOf(3.0)).alongMeters, 2.0)
        assertEquals(700.0, follower.locate(northOf(700.0), eastOf(3.0)).alongMeters, 2.0)
        // The turn, then back down: the same places now match the way back.
        assertEquals(1_000.0, follower.locate(northOf(1_000.0), eastOf(0.0)).alongMeters, 2.0)
        assertEquals(1_300.0, follower.locate(northOf(700.0), eastOf(-3.0)).alongMeters, 2.0)
        assertEquals(1_700.0, follower.locate(northOf(300.0), eastOf(-3.0)).alongMeters, 2.0)
    }

    @Test
    fun `out and back seen for the first time on the way back picks the way out`() {
        val follower = follower(RouteTestShapes.outAndBack(1_000.0))

        assertEquals(300.0, follower.locate(northOf(300.0), eastOf(0.0)).alongMeters, 2.0)
    }

    @Test
    fun `out and back seen again after a long time puts you on the way back, and keeps you there`() {
        // A 10 km out-and-back: looked at on the way out at km 3, then the screen off until the way
        // back at km 8 - the same place as km 2 on the way out.
        val follower = follower(RouteTestShapes.outAndBack(5_000.0))
        val minute = 60_000L
        assertEquals(3_000.0, follower.locate(northOf(3_000.0), eastOf(0.0), atMillis = 0L).alongMeters, 10.0)

        assertEquals(8_000.0, follower.locate(northOf(2_000.0), eastOf(0.0), atMillis = 60 * minute).alongMeters, 10.0)
        assertEquals(8_010.0, follower.locate(northOf(1_990.0), eastOf(0.0), atMillis = 60 * minute + 7_000L).alongMeters, 10.0)
        assertEquals("and after another long gap", 9_000.0, follower.locate(northOf(1_000.0), eastOf(0.0), atMillis = 75 * minute).alongMeters, 15.0)
    }

    @Test
    fun `a rest of a few minutes on the way out keeps you on the way out`() {
        val follower = follower(RouteTestShapes.outAndBack(5_000.0))
        follower.locate(northOf(3_000.0), eastOf(0.0), atMillis = 0L)

        // Sitting down for five minutes; the next fix a few meters back, as GPS wanders.
        assertEquals(2_995.0, follower.locate(northOf(2_995.0), eastOf(2.0), atMillis = 5 * 60_000L).alongMeters, 10.0)
    }

    @Test
    fun `within two minutes the last match still keeps you on your leg`() {
        val follower = follower(RouteTestShapes.outAndBack(5_000.0))
        follower.locate(northOf(3_000.0), eastOf(0.0), atMillis = 0L)

        // Turned around and walked 1 km back the way you came, with a fix after 100 s (a bicycle, say).
        assertEquals(2_000.0, follower.locate(northOf(2_000.0), eastOf(0.0), atMillis = 100_000L).alongMeters, 10.0)
    }

    @Test
    fun `a gap between segments is no route, and isn't counted along it`() {
        // 0-500 m north, then a second segment from 1,000 to 1,500 m north.
        val first = RouteTestShapes.north(500.0)
        val second = RouteSegment(
            DoubleArray(first.size) { northOf(1_000.0) + (first.latitudes[it] - first.latitudes[0]) },
            first.longitudes.copyOf(),
            first.elevations.copyOf(),
        )
        val follower = follower(first, second)

        // In the gap, 250 m from either end.
        val inGap = follower.locate(northOf(750.0), eastOf(0.0))
        assertEquals(250.0, inGap.offMeters, 2.0)
        assertEquals(1_000.0, inGap.lineLengthMeters, 2.0)
        // On the second segment, 200 m in: 500 + 200 along.
        val onSecond = follower.locate(northOf(1_200.0), eastOf(0.0))
        assertEquals(700.0, onSecond.alongMeters, 2.0)
    }

    @Test
    fun `with several lines, the closest line's own distances`() {
        val dayOne = RouteTestShapes.north(1_000.0)
        val dayTwo = RouteSegment(
            DoubleArray(dayOne.size) { dayOne.latitudes[it] },
            DoubleArray(dayOne.size) { eastOf(5_000.0) },
            dayOne.elevations.copyOf(),
        )
        val follower = RouteFollower(listOf(RouteLine(listOf(dayOne)), RouteLine(listOf(dayTwo))))

        val match = follower.locate(northOf(400.0), eastOf(5_000.0))

        assertEquals(1, match.lineIndex)
        assertEquals(400.0, match.alongMeters, 2.0)
        assertEquals(1_000.0, match.lineLengthMeters, 2.0)
    }
}
