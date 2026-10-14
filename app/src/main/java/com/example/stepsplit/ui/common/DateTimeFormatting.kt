package com.example.stepsplit.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every calendar date in the app is shown as day/month/year with a four-digit year, e.g.
 * "13/09/2026", whatever the phone's region - a US-region phone's own short format would otherwise
 * put the month first ("9/13/26"). Times of day still follow the phone's locale.
 */
private val DayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

/** A date as DD/MM/YYYY - see [DayMonthYear]. */
fun formatDateLabel(date: LocalDate): String = date.format(DayMonthYear)

/** The date as DD/MM/YYYY, then the time of day in the device's locale, both in the device's current timezone. */
@Composable
fun formatSyncTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val dateTime = instant.atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(dateTime)
    return "${formatDateLabel(dateTime.toLocalDate())}, $time"
}

@Composable
fun formatDayLabel(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
}

@Composable
fun formatClockTime(epochSecond: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()).format(formatter)
}

/** Like [formatClockTime], but in an explicit [zoneId] rather than the device's current one - for a trip's own stored `startZoneId`, so its start/end times stay consistent with the date shown alongside them even after a later device timezone change. */
@Composable
fun formatClockTimeInZone(epochSecond: Long, zoneId: ZoneId): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return Instant.ofEpochSecond(epochSecond).atZone(zoneId).format(formatter)
}
