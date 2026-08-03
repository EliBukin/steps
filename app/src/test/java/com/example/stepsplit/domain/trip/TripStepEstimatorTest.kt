package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.classification.MinuteBucket
import org.junit.Assert.assertEquals
import org.junit.Test

class TripStepEstimatorTest {

    @Test
    fun `a trip fully covering one bucket counts its steps exactly`() {
        val buckets = listOf(MinuteBucket(1_000L, 100L))
        val steps = TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_000L, tripEndEpochSecond = 1_060L)
        assertEquals(100.0, steps, 1e-9)
    }

    @Test
    fun `a trip starting mid-minute only counts the overlapping fraction of the first bucket`() {
        val buckets = listOf(MinuteBucket(1_000L, 60L))
        // Trip starts 45 seconds into the minute - only the last 15 seconds (a quarter) overlap.
        val steps = TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_045L, tripEndEpochSecond = 1_060L)
        assertEquals(15.0, steps, 1e-9)
    }

    @Test
    fun `a trip ending mid-minute only counts the overlapping fraction of the last bucket`() {
        val buckets = listOf(MinuteBucket(1_000L, 60L))
        // Trip ends 15 seconds into the minute - only the first quarter overlaps.
        val steps = TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_000L, tripEndEpochSecond = 1_015L)
        assertEquals(15.0, steps, 1e-9)
    }

    @Test
    fun `both a partial first and partial last minute are combined with fully-covered minutes between them`() {
        val buckets = listOf(
            MinuteBucket(1_000L, 60L), // partially overlapped at the start
            MinuteBucket(1_060L, 100L), // fully covered
            MinuteBucket(1_120L, 60L), // partially overlapped at the end
        )
        // Trip: 1030 (halfway through the first bucket) to 1150 (halfway through the last).
        val steps = TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_030L, tripEndEpochSecond = 1_150L)
        assertEquals(30.0 + 100.0 + 30.0, steps, 1e-9)
    }

    @Test
    fun `a bucket entirely outside the trip window contributes nothing`() {
        val buckets = listOf(MinuteBucket(500L, 999L))
        val steps = TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_000L, tripEndEpochSecond = 1_060L)
        assertEquals(0.0, steps, 1e-9)
    }

    @Test
    fun `an empty bucket list yields zero steps`() {
        assertEquals(0.0, TripStepEstimator.estimateSteps(emptyList(), 1_000L, 1_060L), 1e-9)
    }

    @Test
    fun `a non-positive trip window yields zero steps`() {
        val buckets = listOf(MinuteBucket(1_000L, 100L))
        assertEquals(0.0, TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_060L, tripEndEpochSecond = 1_060L), 1e-9)
        assertEquals(0.0, TripStepEstimator.estimateSteps(buckets, tripStartEpochSecond = 1_100L, tripEndEpochSecond = 1_060L), 1e-9)
    }
}
