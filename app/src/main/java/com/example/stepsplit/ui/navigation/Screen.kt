package com.example.stepsplit.ui.navigation

sealed class Screen(val route: String) {
    data object Today : Screen("today")
    data object History : Screen("history")
    data object Stats : Screen("stats")
    data object Trips : Screen("trips")

    /** The Map tab: where you are, and the trip being recorded. */
    data object OpenMap : Screen("map")

    /** Not a bottom-nav destination - opened from the gear icon in every tab's header. */
    data object Settings : Screen("settings")

    /** Not a bottom-nav destination - reached only by tapping a trip card on [Trips]. */
    data object TripDetail : Screen("trips/{tripId}") {
        const val ARG_TRIP_ID = "tripId"
        fun createRoute(tripId: Long) = "trips/$tripId"
    }
}

/** Map in the middle, Stats last. */
val bottomNavScreens = listOf(Screen.Today, Screen.History, Screen.OpenMap, Screen.Trips, Screen.Stats)

/** Whether this bottom-bar tab is highlighted while [route] is on screen: its own route, and Trips for an open trip too. */
fun Screen.isSelectedFor(route: String?): Boolean =
    route == this.route || (this == Screen.Trips && route == Screen.TripDetail.route)

/**
 * The route whose tab the bottom bar highlights, from the [currentRoute] and the one under it on the
 * back stack, [previousRoute]: Settings has no tab, so the tab whose gear opened it stays highlighted.
 */
fun highlightedRoute(currentRoute: String?, previousRoute: String?): String? =
    if (currentRoute == Screen.Settings.route) previousRoute else currentRoute
