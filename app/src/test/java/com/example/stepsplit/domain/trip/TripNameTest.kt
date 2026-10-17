package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripNameTest {

    @Test
    fun `no name, a blank name and only spaces are no name`() {
        assertNull(TripName.normalize(null))
        assertNull(TripName.normalize(""))
        assertNull(TripName.normalize("   \n\t "))
    }

    @Test
    fun `the ends are trimmed and inner runs of whitespace become one space`() {
        assertEquals("Wadi Qelt hike", TripName.normalize("  Wadi   Qelt\nhike \t"))
    }

    @Test
    fun `control characters are dropped like whitespace`() {
        assertEquals("Wadi Qelt", TripName.normalize("Wadi\u0000\u0007Qelt\u001B"))
    }

    @Test
    fun `a Hebrew name with an emoji is kept as typed`() {
        assertEquals("טיול בוואדי קלט 🥾", TripName.normalize("טיול בוואדי קלט 🥾"))
    }

    @Test
    fun `a long name is cut to 60 characters`() {
        val name = TripName.normalize("a".repeat(100))!!
        assertEquals(TripName.MAX_LENGTH, name.length)
    }

    @Test
    fun `cutting never splits an emoji in half`() {
        // 59 letters, then a two-character emoji that would straddle the limit.
        val name = TripName.normalize("a".repeat(59) + "🥾")!!
        assertEquals("a".repeat(59), name)
    }

    @Test
    fun `a space left at the cut is trimmed`() {
        assertEquals("a".repeat(59), TripName.normalize("a".repeat(59) + " bcd"))
    }
}
