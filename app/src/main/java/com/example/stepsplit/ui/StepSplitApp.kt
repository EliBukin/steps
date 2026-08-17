package com.example.stepsplit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.example.stepsplit.ui.stats.StatsScreen
import com.example.stepsplit.ui.stats.StatsViewModel
import com.example.stepsplit.ui.today.TodayScreen
import com.example.stepsplit.ui.today.TodayViewModel
import com.example.stepsplit.ui.trips.TripDetailScreen
import com.example.stepsplit.ui.trips.TripDetailViewModel
import com.example.stepsplit.ui.trips.TripsScreen
import com.example.stepsplit.ui.trips.TripsViewModel
import kotlinx.coroutines.launch

@Composable
fun StepSplitApp(
    container: AppContainer,
    onRequestPermission: (onResult: (Boolean) -> Unit) -> Unit,
    onRequestTripPermissions: (onResult: (Map<String, Boolean>) -> Unit) -> Unit,
    navigationEvent: TripNotificationNavigationEvent? = null,
) {
    val navController = rememberNavController()
    val factory = remember(container) { ViewModelFactory(container) }
    val coroutineScope = rememberCoroutineScope()

    // Set by MainActivity when launched/redelivered from the trip-recording notification -
    // navigates there once per event rather than changing the NavHost's own start destination, so
    // the normal back stack/tab-switching behavior is otherwise unaffected. Keyed on the event's
    // own [TripNotificationNavigationEvent.id] rather than its route string: two consecutive taps
    // both targeting "trips" would otherwise share the same key (and the underlying
    // `mutableStateOf` write wouldn't even trigger recomposition, since it'd be writing an
    // identical value) and the second tap's navigation would silently never fire.
    LaunchedEffect(navigationEvent?.id) {
        val route = navigationEvent?.route ?: return@LaunchedEffect
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

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
                    // Immediately re-checks availability and attempts a sync the moment the
                    // permission is actually granted. The system consent dialog launched by
                    // MainActivity.requestHealthConnectPermission also pauses and resumes this
                    // Activity, so TodayScreen's own resume effect fires an overlapping refresh()
                    // around the same moment - viewModel.refresh() coalesces that redundancy away
                    // rather than either caller needing to avoid the other (see its own doc
                    // comment).
                    onGrantPermission = { onRequestPermission { granted -> if (granted) viewModel.refresh() } },
                )
            }
            composable(Screen.History.route) {
                val viewModel: HistoryViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                HistoryScreen(uiState = uiState, onRefresh = viewModel::refresh)
            }
            composable(Screen.Stats.route) {
                val viewModel: StatsViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                StatsScreen(uiState = uiState, onRefresh = viewModel::refresh)
            }
            composable(Screen.Sessions.route) {
                val viewModel: SessionsViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                SessionsScreen(uiState = uiState, onRefresh = viewModel::refresh, onReclassify = viewModel::reclassify)
            }
            composable(Screen.Trips.route) {
                val viewModel: TripsViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                TripsScreen(
                    uiState = uiState,
                    onOpenTrip = { tripId -> navController.navigate(Screen.TripDetail.createRoute(tripId)) },
                    onFinishInterruptedTripAtLastPoint = viewModel::finishInterruptedTripAtLastPoint,
                    onRequestTripPermissions = onRequestTripPermissions,
                )
            }
            composable(
                route = Screen.TripDetail.route,
                arguments = listOf(navArgument(Screen.TripDetail.ARG_TRIP_ID) { type = NavType.LongType }),
            ) { backStackEntry ->
                val tripId = backStackEntry.arguments?.getLong(Screen.TripDetail.ARG_TRIP_ID) ?: return@composable
                val detailFactory = remember(container, tripId) { factory.forTrip(tripId) }
                val viewModel: TripDetailViewModel = viewModel(factory = detailFactory, key = "trip_detail_$tripId")
                val uiState by viewModel.uiState.collectAsState()
                TripDetailScreen(
                    uiState = uiState,
                    onDeleteTrip = viewModel::deleteTrip,
                    onBack = { navController.popBackStack() },
                )
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
                    onRunStepSourceCheck = viewModel::runStepSourceCheck,
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
    Screen.Stats -> Icons.Filled.Star
    Screen.Sessions -> Icons.AutoMirrored.Filled.List
    Screen.Trips -> Icons.Filled.Place
    Screen.Settings -> Icons.Filled.Settings
    is Screen.TripDetail -> Icons.Filled.Place
}

private fun Screen.labelRes(): Int = when (this) {
    Screen.Today -> R.string.nav_today
    Screen.History -> R.string.nav_history
    Screen.Stats -> R.string.nav_stats
    Screen.Sessions -> R.string.nav_sessions
    Screen.Trips -> R.string.nav_trips
    Screen.Settings -> R.string.nav_settings
    is Screen.TripDetail -> R.string.nav_trips
}
