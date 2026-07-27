package com.example.stepsplit.domain.time

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekWindowTest {

    @Test
    fun `week starts on Sunday regardless of where in the week the date falls`() {
        // 2026-03-10 is a Tuesday; the week's Sunday is 2026-03-08.
        val tuesday = LocalDate.of(2026, 3, 10)
        assertEquals(LocalDate.of(2026, 3, 8), WeekWindow.startOfWeek(tuesday))
    }

    @Test
    fun `a Sunday is its own week start`() {
        val sunday = LocalDate.of(2026, 3, 8)
        assertEquals(sunday, WeekWindow.startOfWeek(sunday))
    }

    @Test
    fun `a Saturday is the last day of its week`() {
        val saturday = LocalDate.of(2026, 3, 14)
        assertEquals(LocalDate.of(2026, 3, 8), WeekWindow.startOfWeek(saturday))
        assertEquals(saturday, WeekWindow.endOfWeek(saturday))
    }

    @Test
    fun `week end is always six days after week start`() {
        val anyDate = LocalDate.of(2026, 7, 27)
        val start = WeekWindow.startOfWeek(anyDate)
        assertEquals(start.plusDays(6), WeekWindow.endOfWeek(anyDate))
    }
}
