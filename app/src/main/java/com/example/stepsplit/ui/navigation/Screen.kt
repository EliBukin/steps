package com.example.stepsplit.ui.navigation

sealed class Screen(val route: String) {
    data object Today : Screen("today")
    data object History : Screen("history")
    data object Stats : Screen("stats")
    data object Sessions : Screen("sessions")
    data object Trips : Screen("trips")
    data object Settings : Screen("settings")

    /** Not a bottom-nav destination - reached only by tapping a trip card on [Trips]. */
    data object TripDetail : Screen("trips/{tripId}") {
        const val ARG_TRIP_ID = "tripId"
        fun createRoute(tripId: Long) = "trips/$tripId"
    }
}

val bottomNavScreens = listOf(Screen.Today, Screen.History, Screen.Stats, Screen.Sessions, Screen.Trips, Screen.Settings)
