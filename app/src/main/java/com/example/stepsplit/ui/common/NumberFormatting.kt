package com.example.stepsplit.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** A whole count with the locale's thousands separator, e.g. "18,849" - plain-JVM testable. */
internal fun formatCount(value: Long, locale: Locale): String = String.format(locale, "%,d", value)

/** A step count (or any whole count) with thousands separators, in the app's current locale. */
@Composable
fun formatCount(value: Long): String = formatCount(value, LocalConfiguration.current.locales[0])
