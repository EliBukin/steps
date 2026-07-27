package com.example.stepsplit.domain.aggregation

import java.time.LocalDate

/** A half-open time interval [startEpochSecond, endEpochSecondExclusive). */
data class EpochInterval(val startEpochSecond: Long, val endEpochSecondExclusive: Long) {
    init {
        require(endEpochSecondExclusive > startEpochSecond) {
            "endEpochSecondExclusive must be after startEpochSecond"
        }
    }

    operator fun contains(epochSecond: Long): Boolean =
        epochSecond >= startEpochSecond && epochSecond < endEpochSecondExclusive
}

/** A single one-minute raw step bucket tagged with the local calendar date it was recorded on. */
data class DatedBucket(
    val localDate: LocalDate,
    val startEpochSecond: Long,
    val steps: Long,
)

data class DateStepBreakdown(
    val date: LocalDate,
    val totalSteps: Long,
    val workoutSteps: Long,
    val incidentalSteps: Long,
) {
    init {
        check(totalSteps == workoutSteps + incidentalSteps) {
            "Invariant violated: totalSteps must equal workoutSteps + incidentalSteps"
        }
    }
}

/**
 * Splits raw step buckets into workout vs. incidental totals per local calendar day. A bucket's
 * `localDate` is fixed at import time (see StepBucketEntity), so a walking bout that crosses
 * midnight still attributes each minute's steps to the correct day - the aggregation only needs
 * to know which minutes fall inside a "workout" interval.
 */
object StepAggregator {

    fun mergeIntervals(intervals: List<EpochInterval>): List<EpochInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.startEpochSecond }
        val merged = mutableListOf<EpochInterval>()
        var start = sorted.first().startEpochSecond
        var end = sorted.first().endEpochSecondExclusive
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startEpochSecond <= end) {
                end = maxOf(end, next.endEpochSecondExclusive)
            } else {
                merged.add(EpochInterval(start, end))
                start = next.startEpochSecond
                end = next.endEpochSecondExclusive
            }
        }
        merged.add(EpochInterval(start, end))
        return merged
    }

    fun aggregateByDate(
        buckets: List<DatedBucket>,
        workoutIntervals: List<EpochInterval>,
    ): Map<LocalDate, DateStepBreakdown> {
        val merged = mergeIntervals(workoutIntervals)
        var mergedIndex = 0
        val totals = linkedMapOf<LocalDate, LongArray>()

        for (bucket in buckets.sortedBy { it.startEpochSecond }) {
            while (mergedIndex < merged.size && bucket.startEpochSecond >= merged[mergedIndex].endEpochSecondExclusive) {
                mergedIndex++
            }
            val isWorkout = mergedIndex < merged.size && bucket.startEpochSecond in merged[mergedIndex]
            val totalsForDay = totals.getOrPut(bucket.localDate) { longArrayOf(0L, 0L) }
            totalsForDay[0] += bucket.steps
            if (isWorkout) totalsForDay[1] += bucket.steps
        }

        return totals.mapValues { (date, totalsForDay) ->
            DateStepBreakdown(
                date = date,
                totalSteps = totalsForDay[0],
                workoutSteps = totalsForDay[1],
                incidentalSteps = totalsForDay[0] - totalsForDay[1],
            )
        }
    }
}
