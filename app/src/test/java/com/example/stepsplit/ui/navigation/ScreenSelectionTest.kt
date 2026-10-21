package com.example.stepsplit.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSelectionTest {

    @Test
    fun `each tab is highlighted on its own screen only`() {
        bottomNavScreens.forEach { screen ->
            assertEquals(listOf(screen), bottomNavScreens.filter { it.isSelectedFor(screen.route) })
        }
    }

    @Test
    fun `an open trip keeps Trips highlighted`() {
        assertTrue(Screen.Trips.isSelectedFor(Screen.TripDetail.route))
        assertEquals(listOf(Screen.Trips), bottomNavScreens.filter { it.isSelectedFor(Screen.TripDetail.route) })
    }

    @Test
    fun `the map keeps Trips highlighted`() {
        assertEquals(listOf(Screen.Trips), bottomNavScreens.filter { it.isSelectedFor(Screen.OpenMap.route) })
    }

    @Test
    fun `nothing is highlighted before navigation starts`() {
        bottomNavScreens.forEach { assertFalse(it.isSelectedFor(null)) }
    }
}
