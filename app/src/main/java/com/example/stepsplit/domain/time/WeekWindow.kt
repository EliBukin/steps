package com.example.stepsplit.domain.time

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The app's calendar week is Sunday through Saturday, independent of the device locale's
 * `WeekFields` (which for many locales starts on Monday). `DayOfWeek.value` is 1=Monday..7=Sunday,
 * so `value % 7` maps Sunday to 0 and Saturday to 6 - exactly the offset back to that week's Sunday.
 */
object WeekWindow {
    fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value % 7).toLong())

    fun endOfWeek(date: LocalDate): LocalDate = startOfWeek(date).plusDays(6)

    fun isSunday(date: LocalDate): Boolean = date.dayOfWeek == DayOfWeek.SUNDAY
}
