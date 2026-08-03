package com.example.stepsplit.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/** Uses the device's current locale and timezone, as required for all date/time display. */
@Composable
fun formatSyncTime(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
fun formatDayLabel(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
}

@Composable
fun formatDateLabel(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
    return date.format(formatter)
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
