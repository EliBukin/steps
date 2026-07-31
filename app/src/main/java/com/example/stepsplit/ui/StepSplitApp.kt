package com.example.stepsplit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.stepsplit.R
import com.example.stepsplit.debug.DebugDataSeeder
import com.example.stepsplit.di.AppContainer
import com.example.stepsplit.di.ViewModelFactory
import com.example.stepsplit.ui.history.HistoryScreen
import com.example.stepsplit.ui.history.HistoryViewModel
import com.example.stepsplit.ui.navigation.Screen
import com.example.stepsplit.ui.navigation.bottomNavScreens
import com.example.stepsplit.ui.sessions.SessionsScreen
import com.example.stepsplit.ui.sessions.SessionsViewModel
import com.example.stepsplit.ui.settings.SettingsScreen
import com.example.stepsplit.ui.settings.SettingsViewModel
import com.example.stepsplit.ui.today.TodayScreen
import com.example.stepsplit.ui.today.TodayViewModel
import kotlinx.coroutines.launch

@Composable
fun StepSplitApp(container: AppContainer, onRequestPermission: () -> Unit) {
    val navController = rememberNavController()
    val factory = remember(container) { ViewModelFactory(container) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        bottomBar = { StepSplitBottomBar(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Today.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Today.route) {
                val viewModel: TodayViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()

                TodayScreen(
                    uiState = uiState,
                    onRefresh = viewModel::refresh,
                    onGrantPermission = onRequestPermission,
                )
            }
            composable(Screen.History.route) {
                val viewModel: HistoryViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                HistoryScreen(uiState = uiState, onRefresh = viewModel::refresh)
            }
            composable(Screen.Sessions.route) {
                val viewModel: SessionsViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                SessionsScreen(uiState = uiState, onRefresh = viewModel::refresh, onReclassify = viewModel::reclassify)
            }
            composable(Screen.Settings.route) {
                val viewModel: SettingsViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                SettingsScreen(
                    uiState = uiState,
                    onSetDailyGoal = viewModel::setDailyGoal,
                    onSetThresholds = viewModel::setThresholds,
                    onResetThresholds = viewModel::resetThresholds,
                    onGenerateSampleData = {
                        coroutineScope.launch { DebugDataSeeder.seed(container) }
                    },
                )
            }
        }
    }
}

@Composable
private fun StepSplitBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        bottomNavScreens.forEach { screen ->
            NavigationBarItem(
                selected = currentRoute == screen.route,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(imageVector = screen.icon(), contentDescription = null) },
                label = { Text(stringResource(screen.labelRes())) },
            )
        }
    }
}

private fun Screen.icon(): ImageVector = when (this) {
    Screen.Today -> Icons.Filled.Home
    Screen.History -> Icons.Filled.DateRange
    Screen.Sessions -> Icons.AutoMirrored.Filled.List
    Screen.Settings -> Icons.Filled.Settings
}

private fun Screen.labelRes(): Int = when (this) {
    Screen.Today -> R.string.nav_today
    Screen.History -> R.string.nav_history
    Screen.Sessions -> R.string.nav_sessions
    Screen.Settings -> R.string.nav_settings
}
