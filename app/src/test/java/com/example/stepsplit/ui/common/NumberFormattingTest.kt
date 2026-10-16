package com.example.stepsplit.ui.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormattingTest {

    @Test
    fun `counts get a thousands separator in English`() {
        assertEquals("0", formatCount(0, Locale.US))
        assertEquals("999", formatCount(999, Locale.US))
        assertEquals("2,949", formatCount(2_949, Locale.US))
        assertEquals("105,000", formatCount(105_000, Locale.US))
        assertEquals("1,234,567", formatCount(1_234_567, Locale.US))
    }

    @Test
    fun `counts get the same separator in Hebrew`() {
        assertEquals("18,849", formatCount(18_849, Locale.forLanguageTag("he")))
    }
}
