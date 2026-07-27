package com.example.stepsplit.ui.navigation

sealed class Screen(val route: String) {
    data object Today : Screen("today")
    data object History : Screen("history")
    data object Sessions : Screen("sessions")
    data object Settings : Screen("settings")
}

val bottomNavScreens = listOf(Screen.Today, Screen.History, Screen.Sessions, Screen.Settings)
