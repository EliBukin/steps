package com.example.stepsplit.ui.common

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DateTimeFormattingTest {

    @Test
    fun `dates are day, month, then four-digit year, zero-padded`() {
        assertEquals("13/09/2026", formatDateLabel(LocalDate.of(2026, 9, 13)))
        assertEquals("05/01/2027", formatDateLabel(LocalDate.of(2027, 1, 5)))
        assertEquals("31/12/2026", formatDateLabel(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `the format does not follow a US default locale's month-first order`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("13/09/2026", formatDateLabel(LocalDate.of(2026, 9, 13)))
        } finally {
            Locale.setDefault(original)
        }
    }
}
