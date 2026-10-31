package com.example.stepsplit.data.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A trip's distance across a gap on screen (its recorded points sanitized and smoothed, as every
 * screen, the GPX file and Stats show it), in the code re-review's probes: a gap walked counts, a gap driven counts, a drive between two walks
 * counts nothing, and one glitched speed or one stray fix changes none of that. See
 * [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy]'s "After a gap" and "A gap driven".
 * Made-up points in open sea. (Until review 2's C1 the trip also kept a live distance, checked here
 * too; no screen showed it, so it is gone.)
 */
@RunWith(RobolectricTestRunner::class)
class TripGapDistanceTest {

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    private lateinit var database: StepSplitDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StepSplitDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val t0 = 1_800_000_000L

    private fun north(meters: Double) = 10.0 + meters / 111_195.0

    private fun fix(meters: Double, t: Long, speed: Float, accuracy: Float = 8f) = RawLocationSample(north(meters), 20.0, accuracy, t, speedMetersPerSecond = speed)

    /** The on-screen distance of a trip recorded from [fixes], one per batch; [interruptBefore]: the app is killed before that fix and the trip resumed. */
    private suspend fun record(fixes: List<RawLocationSample>, interruptBefore: Int? = null): Double {
        val clock = MutableClock(Instant.ofEpochSecond(fixes.first().capturedAtEpochSecond))
        val repository = TripRepository(database, clock)
        val tripId = repository.startTrip()
        for ((index, fix) in fixes.withIndex()) {
            if (index == interruptBefore) {
                repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
                clock.instant = Instant.ofEpochSecond(fix.capturedAtEpochSecond - 1)
                check(repository.resumeInterruptedTrip(tripId))
            }
            clock.instant = Instant.ofEpochSecond(fix.capturedAtEpochSecond + 1)
            repository.recordAcceptedBatch(tripId, listOf(fix))
        }
        return RouteSmoother.smooth(RouteSanitizer.sanitize(repository.getTripPoints(tripId)).points).distanceMeters
    }

    private fun assertDistance(expected: Double, onScreen: Double, tolerance: Double) {
        assertEquals("on screen", expected, onScreen, tolerance)
    }

    /** 15 m/s, slowing to a stop at a light, a 1.5 km tunnel (100 s), a queue at 1 m/s, then 15 m/s. About 900 + 40 + 1,500 + 30 + 900 m. */
    @Test
    fun `a tunnel between two red lights counts as the drive it was`() = runTest {
        val fixes = buildList {
            var at = 0.0
            var t = t0
            repeat(30) { add(fix(at, t, 15f, 6f)); at += 30.0; t += 2 }
            for (v in listOf(10f, 6f, 3f, 1f)) { add(fix(at, t, v, 6f)); at += v * 2; t += 2 }
            repeat(10) { add(fix(at + (it % 2), t, 0.1f, 6f)); t += 2 }
            at += 1_500.0
            t += 100
            repeat(15) { add(fix(at, t, 1f)); at += 2.0; t += 2 }
            repeat(30) { add(fix(at, t, 15f, 6f)); at += 30.0; t += 2 }
        }

        assertDistance(3_340.0, record(fixes), 40.0)
    }

    /** Walk 2 min, the app is killed, a 20 km drive (20 min), Resume, walk 5 km. [glitchAt]: one walking fix that many seconds before the kill reports 7 m/s. */
    private fun driveBetweenWalks(glitchAt: Long? = null) = buildList {
        for (s in 0..120 step 5) add(fix(1.3 * s, t0 + s, if (glitchAt != null && s.toLong() == 120 - glitchAt) 7f else 1.3f))
        val resumeAt = t0 + 120 + 20 * 60
        for (s in 0..3_846 step 10) add(fix(1.3 * 120 + 20_000.0 + 1.3 * s, resumeAt + s, 1.3f))
    }

    @Test
    fun `a Resume after a 20 km drive between two walks counts the walks only`() = runTest {
        // 156 m before, 5,000 m after.
        assertDistance(5_156.0, record(driveBetweenWalks(), interruptBefore = 25), 30.0)
    }

    @Test
    fun `one walking fix with a glitched 7 m per s before the drive doesn't make the drive count`() = runTest {
        assertDistance(5_156.0, record(driveBetweenWalks(glitchAt = 30), interruptBefore = 25), 30.0)
    }

    @Test
    fun `a three-minute walking dropout counts its straight line`() = runTest {
        val fixes = (0..600 step 5).filter { it !in 200..379 }.map { s -> fix(1.3 * s, t0 + s, 1.3f) }

        assertDistance(780.0, record(fixes), 5.0)
    }

    /** 15 m/s, a 2-minute stop at a light (reporting 0), one multipath fix 1 km off 76 s into it (30 m accuracy), then 15 m/s. About 900 + 40 + 900 m. */
    @Test
    fun `one stray fix during a long stop at a light adds nothing`() = runTest {
        val fixes = buildList {
            var at = 0.0
            var t = t0
            repeat(30) { add(fix(at, t, 15f, 6f)); at += 30.0; t += 2 }
            for (v in listOf(10f, 6f, 3f, 1f)) { add(fix(at, t, v, 6f)); at += v * 2; t += 2 }
            val stopAt = t
            while (t < stopAt + 120) {
                add(if (t == stopAt + 76) fix(at + 1_000.0, t, 0.5f, 30f) else fix(at + (t % 2), t, 0f, 6f))
                t += 4
            }
            repeat(30) { add(fix(at, t, 15f, 6f)); at += 30.0; t += 2 }
        }

        assertDistance(1_810.0, record(fixes), 40.0)
    }

    @Test
    fun `an accurate stray fix during a long stop, that the next fix doesn't follow, adds nothing either`() = runTest {
        val fixes = buildList {
            var at = 0.0
            var t = t0
            repeat(30) { add(fix(at, t, 15f, 6f)); at += 30.0; t += 2 }
            for (v in listOf(10f, 6f, 3f, 1f)) { add(fix(at, t, v, 6f)); at += v * 2; t += 2 }
            val stopAt = t
            while (t < stopAt + 120) {
                add(if (t == stopAt + 76) fix(at + 1_000.0, t, 0.5f, 6f) else fix(at + (t % 2), t, 0f, 6f))
                t += 4
            }
            repeat(30) { add(fix(at, t, 15f, 6f)); at += 30.0; t += 2 }
        }

        assertDistance(1_810.0, record(fixes), 40.0)
    }
}
