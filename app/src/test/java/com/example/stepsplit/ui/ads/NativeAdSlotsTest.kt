package com.example.stepsplit.ui.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAdSlotsTest {

    /** Ads are plain strings here; each load waits in [pending] until the test delivers it. */
    private val pending = mutableListOf<(String) -> Unit>()
    private val destroyed = mutableListOf<String>()
    private val slots = NativeAdSlots<String>(maxAds = 5, load = { pending += it }, destroy = { destroyed += it })

    @Test
    fun `nothing is loaded until a slot asks`() {
        assertEquals(0, pending.size)
        assertNull(slots.adFor(0))
    }

    @Test
    fun `each slot gets its own ad`() {
        slots.request(0)
        slots.request(1)
        assertEquals(2, pending.size)

        pending[0]("ad A")
        pending[1]("ad B")

        assertEquals("ad A", slots.adFor(0))
        assertEquals("ad B", slots.adFor(1))
    }

    @Test
    fun `a slot asks only once, even when it's composed again or its ad never came`() {
        slots.request(0)
        slots.request(0)
        assertEquals(1, pending.size)

        pending[0]("ad A")
        slots.request(0)
        assertEquals(1, pending.size)
        assertEquals("ad A", slots.adFor(0))
    }

    @Test
    fun `at most five slots ask per visit`() {
        (0 until 8).forEach(slots::request)

        assertEquals(5, pending.size)
        pending.forEachIndexed { slot, deliver -> deliver("ad $slot") }
        assertNull(slots.adFor(5))
        assertNull(slots.adFor(7))
    }

    @Test
    fun `leaving the screen destroys every ad, and one that arrives later`() {
        slots.request(0)
        slots.request(1)
        slots.request(2)
        pending[0]("ad A")
        pending[1]("ad B")

        slots.destroyAll()
        pending[2]("ad C")

        assertEquals(listOf("ad A", "ad B", "ad C"), destroyed)
        assertNull(slots.adFor(0))
        assertNull(slots.adFor(2))
    }

    @Test
    fun `nothing is asked for after the screen left`() {
        slots.destroyAll()
        slots.request(0)
        assertEquals(0, pending.size)
    }
}
