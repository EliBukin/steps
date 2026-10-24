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
    fun `the bottom bar is Today, History, Map, Trips and Stats, with no Settings tab`() {
        assertEquals(listOf(Screen.Today, Screen.History, Screen.OpenMap, Screen.Trips, Screen.Stats), bottomNavScreens)
    }

    @Test
    fun `Settings keeps the tab it was opened from highlighted`() {
        bottomNavScreens.forEach { tab ->
            val highlighted = highlightedRoute(currentRoute = Screen.Settings.route, previousRoute = tab.route)
            assertEquals(listOf(tab), bottomNavScreens.filter { it.isSelectedFor(highlighted) })
        }
    }

    @Test
    fun `away from Settings the current screen decides`() {
        assertEquals(Screen.OpenMap.route, highlightedRoute(currentRoute = Screen.OpenMap.route, previousRoute = Screen.Trips.route))
        assertEquals(listOf(Screen.Trips), bottomNavScreens.filter { it.isSelectedFor(highlightedRoute(Screen.TripDetail.route, Screen.Trips.route)) })
    }

    @Test
    fun `nothing is highlighted before navigation starts`() {
        bottomNavScreens.forEach { assertFalse(it.isSelectedFor(null)) }
    }
}
