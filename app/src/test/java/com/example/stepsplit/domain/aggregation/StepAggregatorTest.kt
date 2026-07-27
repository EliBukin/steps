package com.example.stepsplit.domain.aggregation

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class StepAggregatorTest {

    private val zone = ZoneOffset.UTC

    private fun epochOf(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).toEpochSecond(zone)

    @Test
    fun `total always equals workout plus incidental`() {
        val date = LocalDate.of(2026, 3, 10)
        val buckets = listOf(
            DatedBucket(date, epochOf(date, 8, 0), 50),
            DatedBucket(date, epochOf(date, 8, 1), 50),
            DatedBucket(date, epochOf(date, 18, 0), 100),
        )
        val workoutIntervals = listOf(EpochInterval(epochOf(date, 18, 0), epochOf(date, 18, 1)))

        val breakdown = StepAggregator.aggregateByDate(buckets, workoutIntervals).getValue(date)

        assertEquals(200L, breakdown.totalSteps)
        assertEquals(100L, breakdown.workoutSteps)
        assertEquals(100L, breakdown.incidentalSteps)
        assertEquals(breakdown.totalSteps, breakdown.workoutSteps + breakdown.incidentalSteps)
    }

    @Test
    fun `a bout crossing midnight still attributes steps to the correct local day`() {
        val day1 = LocalDate.of(2026, 3, 10)
        val day2 = LocalDate.of(2026, 3, 11)
        // A single walking bout from 23:58 to 00:02 - one minute bucket on each side of midnight.
        val buckets = listOf(
            DatedBucket(day1, epochOf(day1, 23, 58), 80),
            DatedBucket(day1, epochOf(day1, 23, 59), 80),
            DatedBucket(day2, epochOf(day2, 0, 0), 80),
            DatedBucket(day2, epochOf(day2, 0, 1), 80),
        )
        val workoutIntervals = listOf(EpochInterval(epochOf(day1, 23, 58), epochOf(day2, 0, 2)))

        val breakdowns = StepAggregator.aggregateByDate(buckets, workoutIntervals)

        assertEquals(160L, breakdowns.getValue(day1).totalSteps)
        assertEquals(160L, breakdowns.getValue(day1).workoutSteps)
        assertEquals(160L, breakdowns.getValue(day2).totalSteps)
        assertEquals(160L, breakdowns.getValue(day2).workoutSteps)
    }

    @Test
    fun `overlapping and adjacent intervals merge without double counting`() {
        val merged = StepAggregator.mergeIntervals(
            listOf(
                EpochInterval(0, 100),
                EpochInterval(50, 150),
                EpochInterval(150, 200),
                EpochInterval(500, 600),
            ),
        )

        assertEquals(listOf(EpochInterval(0, 200), EpochInterval(500, 600)), merged)
    }

    @Test
    fun `day with no workout interval reports everything as incidental`() {
        val date = LocalDate.of(2026, 3, 10)
        val buckets = listOf(DatedBucket(date, epochOf(date, 9, 0), 30))

        val breakdown = StepAggregator.aggregateByDate(buckets, emptyList()).getValue(date)

        assertEquals(30L, breakdown.totalSteps)
        assertEquals(0L, breakdown.workoutSteps)
        assertEquals(30L, breakdown.incidentalSteps)
    }
}
