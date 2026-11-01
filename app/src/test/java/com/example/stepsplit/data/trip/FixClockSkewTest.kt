package com.example.stepsplit.data.trip

import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.domain.trip.RawLocationSample
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
 * A phone whose clock is set wrong (automatic time off) still records a trip (review 2, H1). A fix
 * may carry GNSS time, while the trip's start, its Finish and "now" are on the phone's clock; each
 * fix is stamped on the phone's clock by its age on the monotonic clock ([toRawLocationSample]).
 * Made-up points in open sea.
 */
@RunWith(RobolectricTestRunner::class)
class FixClockSkewTest {

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

    private val start = 1_800_000_000L
    private val bootNanos = 5_000_000_000_000L

    /** What the app stamped a fix with before: its own time. */
    private val fixOwnTime: (Location, Long, Long) -> RawLocationSample = { fix, nowMillis, nowNanos ->
        fix.toRawLocationSample(nowMillis, nowNanos).copy(capturedAtEpochSecond = fix.time / 1000L)
    }

    /**
     * A 2-minute walk north at 1.4 m/s with the phone's clock [phoneAheadSeconds] ahead of the fixes'
     * GNSS time: a fix every 5 s (and a fraction), each delivered 0.8 s later, and Finish 92 s in.
     * Returns the kept points' times, in seconds after Start on the phone's clock.
     */
    private suspend fun recordWalk(
        phoneAheadSeconds: Long,
        stamp: (Location, Long, Long) -> RawLocationSample = { fix, nowMillis, nowNanos -> fix.toRawLocationSample(nowMillis, nowNanos) },
    ): List<Long> {
        val clock = MutableClock(Instant.ofEpochSecond(start))
        val repository = TripRepository(database, clock)
        val tripId = repository.startTrip()
        for (k in 1L..24L) {
            val takenMillis = 5_000L * k + 370L
            if (takenMillis > 92_000L) repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = start + 92)
            val fix = Location("fused").apply {
                latitude = 10.0 + 1.4 * takenMillis / 1000.0 / 111_195.0
                longitude = 20.0
                accuracy = 5f
                speed = 1.4f
                time = (start - phoneAheadSeconds) * 1000L + takenMillis
                elapsedRealtimeNanos = bootNanos + takenMillis * 1_000_000L
            }
            val deliveredMillis = takenMillis + 800L
            clock.instant = Instant.ofEpochMilli(start * 1000L + deliveredMillis)
            repository.recordAcceptedBatch(tripId, listOf(stamp(fix, clock.millis(), bootNanos + deliveredMillis * 1_000_000L)))
        }
        val points = repository.getTripPoints(tripId).map { it.capturedAtEpochSecond - start }
        repository.deleteTrip(tripId)
        return points
    }

    /** Every fix up to Finish (92 s in): 5, 10, ... 90 s after Start. */
    private val walkUpToFinish = (1L..18L).map { 5L * it }

    @Test
    fun `a phone clock 3 minutes fast records the walk, up to Finish`() = runTest {
        assertEquals(walkUpToFinish, recordWalk(phoneAheadSeconds = 180))
        // Stamped with its GNSS time, as before, every fix was before the start (and then 3 minutes old: stale).
        assertEquals(emptyList<Long>(), recordWalk(phoneAheadSeconds = 180, stamp = fixOwnTime))
    }

    @Test
    fun `a phone clock 6 minutes slow records the walk, up to Finish`() = runTest {
        assertEquals(walkUpToFinish, recordWalk(phoneAheadSeconds = -360))
        // Stamped with its GNSS time, as before, every fix was 6 minutes ahead of now: more than the 5 allowed.
        assertEquals(emptyList<Long>(), recordWalk(phoneAheadSeconds = -360, stamp = fixOwnTime))
    }

    @Test
    fun `with the clocks agreeing, the same points are kept as with each fix's own time`() = runTest {
        assertEquals(recordWalk(phoneAheadSeconds = 0, stamp = fixOwnTime), recordWalk(phoneAheadSeconds = 0))
        assertEquals(walkUpToFinish, recordWalk(phoneAheadSeconds = 0))

        // To the second its own time gives it, whatever its age and wherever in the second it falls.
        val nowNanos = bootNanos + 900_000_000_000L
        for (nowMillis in listOf(start * 1000L, start * 1000L + 1, start * 1000L + 999, start * 1000L + 456)) {
            for (ageMillis in listOf(0L, 1L, 999L, 1_000L, 1_001L, 4_321L, 119_999L, 600_000L)) {
                val fix = Location("fused").apply {
                    latitude = 10.0
                    longitude = 20.0
                    accuracy = 5f
                    time = nowMillis - ageMillis
                    elapsedRealtimeNanos = nowNanos - ageMillis * 1_000_000L
                }
                assertEquals("now $nowMillis, age $ageMillis ms", fix.time / 1000L, fix.toRawLocationSample(nowMillis, nowNanos).capturedAtEpochSecond)
            }
        }
    }

    @Test
    fun `a fix without a monotonic time keeps its own time`() {
        val fix = Location("mock").apply {
            latitude = 10.0
            longitude = 20.0
            time = (start - 180) * 1000L
            elapsedRealtimeNanos = 0L
        }

        assertEquals(start - 180, fix.toRawLocationSample(start * 1000L, bootNanos).capturedAtEpochSecond)
    }
}
