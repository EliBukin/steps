package com.example.stepsplit.domain.aggregation

import com.example.stepsplit.domain.classification.MinuteBucket

/** A raw, possibly multi-minute step interval as reported by a step source, before normalization. */
data class RawInterval(
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Long,
)

/**
 * Normalizes raw (possibly multi-minute, possibly overlapping-within-one-read) step intervals
 * into one-minute-aligned buckets, splitting a multi-minute interval's steps evenly (remainder
 * distributed to the earliest minutes) across every minute it spans. Idempotent re-import safety
 * against *previously stored* data is handled separately by the unique (source, startEpochSecond)
 * upsert key - this function only guarantees a single read's intervals combine correctly.
 */
object BucketNormalizer {
    private const val SECONDS_PER_MINUTE = 60L

    fun normalize(intervals: List<RawInterval>): List<MinuteBucket> {
        val perMinute = linkedMapOf<Long, Long>()
        for (interval in intervals) {
            if (interval.steps <= 0) continue
            distributeAcrossMinutes(interval, perMinute)
        }
        return perMinute
            .filterValues { it > 0 }
            .map { (start, steps) -> MinuteBucket(start, steps) }
            .sortedBy { it.startEpochSecond }
    }

    private fun distributeAcrossMinutes(interval: RawInterval, perMinute: MutableMap<Long, Long>) {
        val minuteStart = alignToMinute(interval.startEpochSecond)
        val lastCoveredInstant = (interval.endEpochSecond - 1).coerceAtLeast(interval.startEpochSecond)
        val minuteEnd = alignToMinute(lastCoveredInstant)
        val minuteCount = ((minuteEnd - minuteStart) / SECONDS_PER_MINUTE) + 1

        val baseStepsPerMinute = interval.steps / minuteCount
        val remainder = interval.steps % minuteCount

        for (i in 0 until minuteCount) {
            val minute = minuteStart + i * SECONDS_PER_MINUTE
            val extra = if (i < remainder) 1L else 0L
            perMinute[minute] = (perMinute[minute] ?: 0L) + baseStepsPerMinute + extra
        }
    }

    private fun alignToMinute(epochSecond: Long): Long = epochSecond - Math.floorMod(epochSecond, SECONDS_PER_MINUTE)
}
