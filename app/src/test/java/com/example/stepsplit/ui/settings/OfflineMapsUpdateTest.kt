package com.example.stepsplit.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review 2, D3: "Update available" was a plain string compare (`server > installed`), so some
 * same-day rebuilds were never offered, and a broken pack couldn't be withdrawn. build-pack.sh
 * writes `yyyy-MM-dd`; suffixes for a same-day rebuild are added by hand.
 */
class OfflineMapsUpdateTest {

    @Test
    fun `a newer pack is offered`() {
        assertTrue(offersUpdate("2026-10-31", "2026-10-30c"))
        assertTrue(offersUpdate("2026-11-01", "2026-10-31c"))
        assertTrue(offersUpdate("2026-10-30c", "2026-10-30b"))
        assertTrue(offersUpdate("2026-10-30c", "2026-10-30"))
        assertTrue(offersUpdate("2026-10-30-2", "2026-10-30"))
    }

    @Test
    fun `a rebuild is offered whatever its suffix style`() {
        // A dash after a letter, an upper-case letter, or a 27th build: none sorts after the installed name.
        assertTrue(offersUpdate("2026-10-30-2", "2026-10-30c"))
        assertTrue(offersUpdate("2026-10-30C", "2026-10-30b"))
        assertTrue(offersUpdate("2026-10-30aa", "2026-10-30z"))
    }

    @Test
    fun `publishing the older pack again rolls a broken one back`() {
        assertTrue(offersUpdate("2026-10-29", "2026-10-30"))
    }

    @Test
    fun `the installed version is not offered again`() {
        assertFalse(offersUpdate("2026-10-30", "2026-10-30"))
    }
}
