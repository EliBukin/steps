package com.example.stepsplit.data.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.RouteSanitizer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Do the live gap rules (TripRepository.recordAcceptedBatch with its held fix) and the read-time
 * ones (RouteSanitizer) keep the same points? Randomized trips made of walks, drives, stops, gaps of
 * every kind (walked, driven, a Resume after a drive), far stray fixes, duplicates and batches split
 * at random, delivered in shuffled order inside a batch. The walks and drives are smooth, so the
 * sanitizer's look-ahead and excursion steps (which live acceptance can't do, by design) have
 * nothing to remove: any difference is a gap-rule disagreement. From review 2 (§5.A1, 0 of 150
 * disagreed); kept when the live distance went (C1), so live recording still keeps the same points.
 * Whether a jump counts is read time's alone now. Made-up points in open sea.
 */
@RunWith(RobolectricTestRunner::class)
class GapRuleDifferentialTest {

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }

    private lateinit var database: StepSplitDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StepSplitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val lat0 = 10.0
    private val lon0 = 20.0
    private fun sample(east: Double, north: Double, t: Long, accuracy: Float, speed: Float?) = RawLocationSample(
        latitude = lat0 + north / 111_195.0,
        longitude = lon0 + east / (111_195.0 * cos(Math.toRadians(lat0))),
        accuracyMeters = accuracy,
        capturedAtEpochSecond = t,
        speedMetersPerSecond = speed,
    )

    /** One random trip's fixes, in capture order (strays included). */
    private fun randomTrip(random: Random, strays: Boolean = true): List<RawLocationSample> = buildList {
        var t = 1_800_000_000L
        var east = 0.0
        var north = 0.0
        var heading = random.nextDouble(0.0, 2 * Math.PI)
        var lastSpeed = 1.3f
        fun move(meters: Double) {
            east += meters * sin(heading)
            north += meters * cos(heading)
        }
        repeat(random.nextInt(4, 10)) {
            when (random.nextInt(6)) {
                0, 1 -> { // walk
                    val speed = random.nextDouble(1.0, 1.7)
                    repeat(random.nextInt(8, 50)) {
                        val dt = random.nextLong(5, 11)
                        t += dt
                        heading += random.nextDouble(-0.1, 0.1)
                        move(speed * dt)
                        lastSpeed = (speed + random.nextDouble(-0.1, 0.1)).toFloat()
                        add(sample(east, north, t, random.nextInt(4, 17).toFloat(), lastSpeed))
                    }
                }
                2 -> { // drive
                    val speed = random.nextDouble(8.0, 25.0)
                    repeat(random.nextInt(8, 50)) {
                        val dt = random.nextLong(2, 6)
                        t += dt
                        heading += random.nextDouble(-0.05, 0.05)
                        move(speed * dt)
                        lastSpeed = speed.toFloat()
                        add(sample(east, north, t, random.nextInt(3, 12).toFloat(), lastSpeed))
                    }
                }
                3 -> { // gap: walked, driven, or a Resume after a drive
                    val gap = random.nextLong(61, 1_200)
                    t += gap
                    when (random.nextInt(3)) {
                        0 -> move(1.3 * gap)
                        1 -> move(15.0 * gap)
                        else -> move(random.nextDouble(5_000.0, 30_000.0))
                    }
                }
                4 -> if (strays) { // a far stray fix
                    t += random.nextLong(2, 8)
                    val offset = random.nextDouble(400.0, 3_000.0)
                    val angle = random.nextDouble(0.0, 2 * Math.PI)
                    add(sample(east + offset * sin(angle), north + offset * cos(angle), t, random.nextInt(5, 30).toFloat(), lastSpeed))
                }
                else -> { // a stop: a few drift fixes reporting standing still
                    repeat(random.nextInt(1, 5)) {
                        t += random.nextLong(10, 40)
                        add(sample(east + random.nextDouble(-2.0, 2.0), north + random.nextDouble(-2.0, 2.0), t, 8f, 0.1f))
                    }
                    lastSpeed = 0.1f
                }
            }
        }
    }

    /** Records [fixes] live, in random batches shuffled inside, with now a little after each batch's newest fix; returns the stored points. */
    private suspend fun recordLive(fixes: List<RawLocationSample>, random: Random, oneByOne: Boolean = false): List<com.example.stepsplit.domain.model.TripPoint> {
        val clock = MutableClock(Instant.ofEpochSecond(fixes.first().capturedAtEpochSecond - 1))
        val repository = TripRepository(database, clock)
        val tripId = repository.startTrip()
        var i = 0
        while (i < fixes.size) {
            // A real batch holds at most MAX_UPDATE_DELAY_MILLIS (15 s) of fixes.
            var size = if (oneByOne) 1 else random.nextInt(1, 5).coerceAtMost(fixes.size - i)
            while (size > 1 && fixes[i + size - 1].capturedAtEpochSecond - fixes[i].capturedAtEpochSecond > 15) size--
            val batch = fixes.subList(i, i + size).toMutableList()
            // Now and then the platform redelivers the batch's last fix again.
            if (!oneByOne && random.nextInt(10) == 0) batch += batch.last()
            batch.shuffle(random)
            clock.instant = Instant.ofEpochSecond(fixes[i + size - 1].capturedAtEpochSecond + if (oneByOne) 1 else random.nextLong(0, 6))
            repository.recordAcceptedBatch(tripId, batch)
            i += size
        }
        val points = repository.getTripPoints(tripId)
        repository.deleteTrip(tripId)
        return points
    }

    /**
     * Without stray fixes the sanitizer's look-ahead has nothing to remove: the only point it may drop
     * is a trip's first one (its anchor rule, documented). Every other point live recording kept -
     * the start of each new stretch included - must be kept on screen too.
     */
    @Test
    fun `without stray fixes live and read-time gap rules keep the same points`() = runTest {
        val disagreements = mutableListOf<String>()
        var gapStretches = 0
        for (seed in 1..150) {
            val random = Random(seed)
            val fixes = randomTrip(random, strays = false)
            if (fixes.size < 2) continue
            val stored = recordLive(fixes, random)
            val sanitized = RouteSanitizer.sanitize(stored)
            gapStretches += sanitized.points.count { it.startsStretch }
            val droppedOtherThanFirst = sanitized.rejections.map { it.point }.filter { it != stored.first() }
            if (droppedOtherThanFirst.isNotEmpty()) {
                disagreements += "seed $seed: stored ${stored.size}, kept on screen ${sanitized.points.size}"
            }
        }
        assertTrue("the generator must exercise the new-stretch rule", gapStretches > 10)
        assertEquals(emptyList<String>(), disagreements)
    }

    /**
     * With far stray fixes, live acceptance keeps a stray that is plausible against the last kept
     * fix (a vehicle's reported speed allows 2v+3 m/s; a long gap allows a lot on foot), and the
     * sanitizer's look-ahead then drops it from what the screens show. Nothing else differs: every
     * point on screen is one live recording stored. (50 trips: the 150 above already cover the gap rules.)
     */
    @Test
    fun `with stray fixes the only differences are strays the look-ahead removes`() = runTest {
        for (seed in 1..50) {
            val random = Random(seed)
            val fixes = randomTrip(random)
            if (fixes.size < 2) continue
            val stored = recordLive(fixes, random)
            val sanitized = RouteSanitizer.sanitize(stored)
            assertTrue("seed $seed", sanitized.points.all { kept -> stored.any { it.capturedAtEpochSecond == kept.capturedAtEpochSecond } })
            assertEquals("seed $seed", stored.size, sanitized.points.size + sanitized.rejections.size)
        }
    }

    /** Live acceptance must not depend on how the platform batches the same fixes (30 trips, each recorded twice). */
    @Test
    fun `live recording keeps the same points whatever the batching`() = runTest {
        for (seed in 1..30) {
            val fixes = randomTrip(Random(seed))
            if (fixes.size < 2) continue
            val storedBatched = recordLive(fixes, Random(seed * 7919))
            val storedOneByOne = recordLive(fixes, Random(seed), oneByOne = true)
            assertEquals("seed $seed", storedOneByOne.map { it.capturedAtEpochSecond }, storedBatched.map { it.capturedAtEpochSecond })
        }
    }
}
