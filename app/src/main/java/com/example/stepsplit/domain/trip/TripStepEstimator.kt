package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.classification.MinuteBucket

/**
 * Estimates the steps taken during a trip window from already-synced [MinuteBucket]s (each a
 * fixed 60-second, minute-aligned slice - see [com.example.stepsplit.domain.aggregation.BucketNormalizer]).
 * A trip's start/end are arbitrary second-level timestamps, not minute-aligned, so a minute
 * straddling either boundary only partially overlaps the trip - counting it whole would
 * over-count. Each bucket instead contributes `steps * (overlapSeconds / 60)`, so a minute that is
 * e.g. only 15 seconds inside the trip window contributes a quarter of its steps. This can never
 * be more precise than minute-resolution source data allows, which is exactly why callers must
 * present the result as *estimated*, never exact.
 */
object TripStepEstimator {
    private const val BUCKET_WIDTH_SECONDS = 60L

    fun estimateSteps(
        buckets: List<MinuteBucket>,
        tripStartEpochSecond: Long,
        tripEndEpochSecond: Long,
    ): Double {
        if (tripEndEpochSecond <= tripStartEpochSecond) return 0.0
        var total = 0.0
        for (bucket in buckets) {
            val bucketEnd = bucket.startEpochSecond + BUCKET_WIDTH_SECONDS
            val overlapStart = maxOf(bucket.startEpochSecond, tripStartEpochSecond)
            val overlapEnd = minOf(bucketEnd, tripEndEpochSecond)
            val overlapSeconds = overlapEnd - overlapStart
            if (overlapSeconds <= 0) continue
            total += bucket.steps * (overlapSeconds.toDouble() / BUCKET_WIDTH_SECONDS)
        }
        return total
    }
}
