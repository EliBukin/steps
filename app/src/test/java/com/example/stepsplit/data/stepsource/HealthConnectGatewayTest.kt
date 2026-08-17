package com.example.stepsplit.data.stepsource

import com.example.stepsplit.domain.aggregation.BucketNormalizer
import com.example.stepsplit.domain.aggregation.RawInterval
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [alignedStepIntervalRequestWindows]/[alignDownToMinute] - the exact
 * boundary-computation code [PlatformHealthConnectGateway.readStepIntervals] uses to build every
 * real `aggregateGroupByDuration` request. These tests call that production function directly
 * (not a reimplementation of its math, and not a fake source that only ever returns
 * already-aligned intervals) with clocks carrying nonzero seconds and nanoseconds, since that is
 * exactly the condition under which the real bug occurred: a fresh-database first sync computes
 * its window start from `now.minus(retentionWindow)`, and `now` almost never lands on an exact
 * minute boundary.
 */
class HealthConnectGatewayTest {

    // Deliberately NOT on a minute boundary - 37.123456789 seconds past the minute - the same
    // shape of value `Clock.systemUTC().instant()` would realistically produce.
    private val unalignedNow: Instant = Instant.parse("2026-08-15T12:34:37.123456789Z")

    // Mirrors StepRepository.RETENTION_WINDOW - a fresh/empty database's first-ever sync reads
    // this far back from `now`, so this exercises exactly that "first import on an empty
    // database" scenario the alignment contract must also hold for.
    private val retentionWindow: Duration = Duration.ofDays(10)

    @Test
    fun `alignDownToMinute floors nonzero seconds and nanoseconds down to the minute`() {
        assertEquals(Instant.parse("2026-08-15T12:34:00Z"), alignDownToMinute(unalignedNow))
        assertEquals(Instant.parse("2026-08-15T12:34:00Z"), alignDownToMinute(Instant.parse("2026-08-15T12:34:00.000000001Z")))
        assertEquals(Instant.parse("2026-08-15T12:34:00Z"), alignDownToMinute(Instant.parse("2026-08-15T12:34:59.999999999Z")))
        // Already-aligned input is returned unchanged, not shifted back an extra minute.
        assertEquals(Instant.parse("2026-08-15T12:34:00Z"), alignDownToMinute(Instant.parse("2026-08-15T12:34:00Z")))
    }

    @Test
    fun `fresh-database retention-window import starts on an exact minute boundary`() {
        val fromInclusive = unalignedNow.minus(retentionWindow)
        val windows = alignedStepIntervalRequestWindows(fromInclusive, unalignedNow)

        assertTrue(windows.isNotEmpty())
        val firstStart = windows.first().start
        assertEquals(0L, firstStart.epochSecond % 60)
        assertEquals(0, firstStart.nano)
        // Aligning is a floor, never a ceiling - the aligned start must not be later than what was
        // requested, or real step data at the very edge of the retention window would be dropped -
        // and it must not move back by more than the 59.999... seconds a floor can ever move by.
        assertTrue(!firstStart.isAfter(fromInclusive))
        assertTrue(Duration.between(firstStart, fromInclusive) < Duration.ofMinutes(1))
    }

    @Test
    fun `a ten-day window is split into multiple exactly-12-hour chunks plus one final partial chunk`() {
        val fromInclusive = unalignedNow.minus(retentionWindow)
        val windows = alignedStepIntervalRequestWindows(fromInclusive, unalignedNow)

        // 10 days / 12h = 20 chunks exactly, plus the alignment floor pushes the aligned start
        // ~37 seconds earlier than fromInclusive - just enough leftover span to require one more,
        // shorter, final chunk ending at toExclusive.
        assertEquals(21, windows.size)
        for (window in windows.dropLast(1)) {
            assertEquals(HealthConnectGateway.CHUNK_DURATION, Duration.between(window.start, window.endExclusive))
        }
        assertTrue(Duration.between(windows.last().start, windows.last().endExclusive) < HealthConnectGateway.CHUNK_DURATION)
    }

    @Test
    fun `chunks are exactly adjacent - no overlap, no gap`() {
        val fromInclusive = unalignedNow.minus(retentionWindow)
        val windows = alignedStepIntervalRequestWindows(fromInclusive, unalignedNow)

        for (i in 0 until windows.size - 1) {
            assertEquals(
                "window $i's end must equal window ${i + 1}'s start exactly",
                windows[i].endExclusive,
                windows[i + 1].start,
            )
        }
    }

    @Test
    fun `the final window's end is the unaligned instant itself - an accepted trailing partial minute`() {
        val fromInclusive = unalignedNow.minus(retentionWindow)
        val windows = alignedStepIntervalRequestWindows(fromInclusive, unalignedNow)

        assertEquals(unalignedNow, windows.last().endExclusive)
    }

    @Test
    fun `a window entirely inside one chunk produces a single aligned window ending exactly at toExclusive`() {
        val fromInclusive = Instant.parse("2026-08-15T12:30:07.5Z")
        val toExclusive = Instant.parse("2026-08-15T12:34:37.123Z")

        val windows = alignedStepIntervalRequestWindows(fromInclusive, toExclusive)

        assertEquals(1, windows.size)
        assertEquals(Instant.parse("2026-08-15T12:30:00Z"), windows.single().start)
        assertEquals(toExclusive, windows.single().endExclusive)
    }

    @Test
    fun `a window ending at or before the aligned start produces no request windows at all`() {
        // unalignedNow's own aligned (floored) start is 12:34:00 - a toExclusive at or before that
        // leaves nothing left to request, even though fromInclusive itself is later (12:34:37...).
        val alignedFloorOfNow = Instant.parse("2026-08-15T12:34:00Z")
        assertTrue(alignedStepIntervalRequestWindows(unalignedNow, alignedFloorOfNow).isEmpty())
        assertTrue(alignedStepIntervalRequestWindows(unalignedNow, alignedFloorOfNow.minusSeconds(1)).isEmpty())
    }

    // ---- Downstream effect: aligned windows normalize into clean, unsplit epoch-minute buckets ----

    /**
     * Mirrors exactly how Health Connect's own `aggregateGroupByDuration` behaves for a one-minute
     * slicer applied to one already-aligned request window: buckets start at the window's own
     * start and step by exactly one minute, with a trailing bucket clipped to the window's end
     * (the same shape [PlatformHealthConnectGateway.readStepIntervals] receives from the real
     * SDK). Used here to prove [alignedStepIntervalRequestWindows]' aligned output survives
     * [BucketNormalizer] without being incorrectly split - the actual bug this fix addresses.
     */
    private fun simulateHealthConnectBuckets(window: ClosedOpenInstantRange, stepsPerFullMinute: Long): List<RawStepInterval> {
        val buckets = mutableListOf<RawStepInterval>()
        var bucketStart = window.start
        while (bucketStart < window.endExclusive) {
            val bucketEnd = minOf(bucketStart.plusSeconds(60), window.endExclusive)
            val durationSeconds = bucketEnd.epochSecond - bucketStart.epochSecond
            val steps = stepsPerFullMinute * durationSeconds / 60
            if (steps > 0) buckets += RawStepInterval(bucketStart.epochSecond, bucketEnd.epochSecond, steps)
            bucketStart = bucketEnd
        }
        return buckets
    }

    @Test
    fun `aligned windows normalize into one unsplit MinuteBucket per source interval, preserving total steps`() {
        val fromInclusive = Instant.parse("2026-08-15T00:00:07.25Z")
        val toExclusive = Instant.parse("2026-08-15T13:34:37.123Z") // spans one full 12h chunk boundary + a trailing partial minute

        val windows = alignedStepIntervalRequestWindows(fromInclusive, toExclusive)
        assertTrue("test setup should exercise more than one chunk", windows.size > 1)

        val rawIntervals = windows.flatMap { simulateHealthConnectBuckets(it, stepsPerFullMinute = 100) }
        val totalRawSteps = rawIntervals.sumOf { it.steps }

        val minuteBuckets = BucketNormalizer.normalize(rawIntervals.map { RawInterval(it.startEpochSecond, it.endEpochSecond, it.steps) })

        // The whole point of aligning the request: every simulated Health Connect bucket is
        // already exactly one calendar minute (or a clipped trailing remainder of one), so
        // BucketNormalizer.distributeAcrossMinutes must map each one to exactly ONE MinuteBucket -
        // never split across two, and never merged with an unrelated neighbor.
        assertEquals(rawIntervals.size, minuteBuckets.size)
        for ((raw, bucket) in rawIntervals.zip(minuteBuckets)) {
            assertEquals(raw.startEpochSecond, bucket.startEpochSecond)
            assertEquals(raw.steps, bucket.steps)
        }

        assertEquals(totalRawSteps, minuteBuckets.sumOf { it.steps })
    }
}
