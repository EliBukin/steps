package com.example.stepsplit.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.R
import com.example.stepsplit.debug.DebugDataSeeder
import com.example.stepsplit.di.AppContainer
import com.example.stepsplit.di.ViewModelFactory
import com.example.stepsplit.ui.ads.NativeAdSlot
import com.example.stepsplit.ui.ads.WithBannerAd
import com.example.stepsplit.ui.ads.rememberTripsNativeAds
import com.example.stepsplit.ui.history.HistoryScreen
import com.example.stepsplit.ui.history.HistoryViewModel
import com.example.stepsplit.ui.map.ImportedRouteActions
import com.example.stepsplit.ui.map.LocalMapPacks
import com.example.stepsplit.ui.map.OpenMapScreen
import com.example.stepsplit.ui.map.OpenMapViewModel
import com.example.stepsplit.ui.navigation.Screen
import com.example.stepsplit.ui.navigation.bottomNavScreens
import com.example.stepsplit.ui.navigation.highlightedRoute
import com.example.stepsplit.ui.navigation.isSelectedFor
import com.example.stepsplit.ui.settings.OfflineMapsActions
import com.example.stepsplit.ui.settings.OfflineMapsViewModel
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
    onOpenHealthConnectPermissions: (onReturn: () -> Unit) -> Unit,
    onRequestTripPermissions: (onResult: (Map<String, Boolean>) -> Unit) -> Unit,
    navigationEvent: TripNotificationNavigationEvent? = null,
    /** A GPX file opened with the app or shared to it: the Map tab imports it, then [onRouteImportHandled] with its id. */
    routeImportEvent: RouteImportEvent? = null,
    onRouteImportHandled: (Long) -> Unit = {},
    /** Google's form for changing ad consent (see AdsManager.showPrivacyOptionsForm), from Settings. */
    onShowAdPrivacyOptions: () -> Unit = {},
) {
    val navController = rememberNavController()
    val factory = remember(container) { ViewModelFactory(container) }
    val coroutineScope = rememberCoroutineScope()
    // The gear icon in every tab's header. Settings opens over the tab, which stays highlighted.
    val openSettings: () -> Unit = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } }

    // Set by MainActivity when launched/redelivered from the trip-recording notification -
    // navigates there once per event rather than changing the NavHost's own start destination, so
    // the normal back stack/tab-switching behavior is otherwise unaffected. Keyed on the event's
    // own [TripNotificationNavigationEvent.id] rather than its route string: two consecutive taps
    // both targeting "trips" would otherwise share the same key (and the underlying
    // `mutableStateOf` write wouldn't even trigger recomposition, since it'd be writing an
    // identical value) and the second tap's navigation would silently never fire.
    LaunchedEffect(navigationEvent?.id) {
        val route = navigationEvent?.route ?: return@LaunchedEffect
        navController.navigateToTab(route)
    }
    // A GPX file to import opens the Map tab, which imports it (below) and shows it.
    LaunchedEffect(routeImportEvent?.id) {
        if (routeImportEvent != null) navController.navigateToTab(Screen.OpenMap.route)
    }

    // The offline map packs on the phone, for every map in the app (see rememberMapBackground). With
    // the lifecycle, so a download in progress is asked about less often while the app isn't on screen.
    val mapPacks by container.mapPackRepository.state.collectAsStateWithLifecycle()
    // Whether ads may be requested yet (see AdsManager). Only Stats, History and Trips show any.
    val adsReady by container.ads.ready.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalMapPacks provides mapPacks.installed) {
        Scaffold(
            bottomBar = { StepSplitBottomBar(navController) },
        ) { innerPadding ->
            // Every screen's uiState is collected with the lifecycle: while a recording runs, the
            // process and this composition stay alive with the app in the background, and plain
            // collectAsState would keep each screen re-reading (and, for Trips and Map, re-smoothing)
            // the route on every fix. Collecting stops as the app leaves the screen, and the
            // ViewModel's flows (WhileSubscribed) five seconds later.
            NavHost(
                navController = navController,
                startDestination = Screen.Today.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Screen.Today.route) {
                    val viewModel: TodayViewModel = viewModel(factory = factory)
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                    TodayScreen(
                        uiState = uiState,
                        onRefresh = viewModel::refresh,
                        // Immediately re-checks availability and attempts a sync the moment the
                        // permission is actually granted. The system consent dialog launched by
                        // MainActivity.requestHealthConnectPermission also pauses and resumes this
                        // Activity, so TodayScreen's own resume effect fires an overlapping refresh()
                        // around the same moment - viewModel.refresh() coalesces that redundancy away
                        // rather than either caller needing to avoid the other (see its own doc
                        // comment). A denial switches the banner to Health Connect's own screen.
                        onGrantPermission = { onRequestPermission(viewModel::onPermissionRequestResult) },
                        onOpenHealthConnect = { onOpenHealthConnectPermissions(viewModel::recheckPermission) },
                        onOpenSettings = openSettings,
                    )
                }
                composable(Screen.History.route) {
                    val viewModel: HistoryViewModel = viewModel(factory = factory)
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    WithBannerAd(adsReady = adsReady, adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID) { modifier ->
                        HistoryScreen(uiState = uiState, onRefresh = viewModel::refresh, onOpenSettings = openSettings, modifier = modifier)
                    }
                }
                composable(Screen.Stats.route) {
                    val viewModel: StatsViewModel = viewModel(factory = factory)
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    WithBannerAd(adsReady = adsReady, adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID) { modifier ->
                        StatsScreen(uiState = uiState, onRefresh = viewModel::refresh, onOpenSettings = openSettings, modifier = modifier)
                    }
                }
                composable(Screen.Trips.route) {
                    val viewModel: TripsViewModel = viewModel(factory = factory)
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    // The list's ads, one per slot (see rememberTripsNativeAds). A slot asks for its ad
                    // only once it's on screen; TripsScreen shows slots only with 3 trips or more and
                    // no trip recording.
                    val nativeAds = rememberTripsNativeAds(BuildConfig.ADMOB_NATIVE_UNIT_ID)
                    TripsScreen(
                        uiState = uiState,
                        onOpenTrip = { tripId -> navController.navigate(Screen.TripDetail.createRoute(tripId)) },
                        onFinishInterruptedTripAtLastPoint = viewModel::finishInterruptedTripAtLastPoint,
                        onRequestTripPermissions = onRequestTripPermissions,
                        onMarkSpot = viewModel::markSpot,
                        spotMarkResults = viewModel.spotMarkResults,
                        onOpenSettings = openSettings,
                        adSlot = if (adsReady && BuildConfig.ADMOB_NATIVE_UNIT_ID.isNotEmpty()) {
                            { slot -> NativeAdSlot(nativeAds, slot) }
                        } else {
                            null
                        },
                    )
                }
                composable(Screen.OpenMap.route) {
                    val viewModel: OpenMapViewModel = viewModel(factory = factory)
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    // Collected only while this tab's back stack entry is at least started: switching to
                    // another tab or leaving the app stops the location request at once (see
                    // OpenMapViewModel.location).
                    val location by viewModel.location.collectAsStateWithLifecycle()
                    // The same: only while the tab is on screen (see OpenMapViewModel.routeStrip).
                    val routeStrip by viewModel.routeStrip.collectAsStateWithLifecycle()
                    val importStatus by viewModel.importStatus.collectAsState()
                    val zoomRequest by viewModel.zoomRequest.collectAsState()
                    val currentOnRouteImportHandled by rememberUpdatedState(onRouteImportHandled)
                    LaunchedEffect(routeImportEvent?.id) {
                        val event = routeImportEvent ?: return@LaunchedEffect
                        viewModel.importRoute(event.uri)
                        currentOnRouteImportHandled(event.id)
                    }
                    OpenMapScreen(
                        uiState = uiState,
                        location = location,
                        onLocationAvailableChange = viewModel::setLocationAvailable,
                        onOpenSettings = openSettings,
                        offerOfflineMap = mapPacks.installed.isEmpty() && !mapPacks.offerAnswered,
                        onAnswerOfflineMapOffer = { download ->
                            container.mapPackRepository.answerOffer()
                            if (download) openSettings()
                        },
                        routeStrip = routeStrip,
                        importStatus = importStatus,
                        onDismissImportStatus = viewModel::dismissImportStatus,
                        onImportRoute = viewModel::importRoute,
                        routeActions = remember(viewModel) {
                            ImportedRouteActions(
                                onShowChange = { route, shown -> viewModel.setRouteShown(route.id, shown) },
                                onZoomTo = viewModel::zoomToRoute,
                                onRename = { route, name -> viewModel.renameRoute(route.id, name) },
                                onDelete = { route -> viewModel.deleteRoute(route.id) },
                            )
                        },
                        zoomRequest = zoomRequest,
                        onZoomRequestHandled = viewModel::onZoomRequestHandled,
                    )
                }
                composable(
                    route = Screen.TripDetail.route,
                    arguments = listOf(navArgument(Screen.TripDetail.ARG_TRIP_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val tripId = backStackEntry.arguments?.getLong(Screen.TripDetail.ARG_TRIP_ID) ?: return@composable
                    val detailFactory = remember(container, tripId) { factory.forTrip(tripId) }
                    val viewModel: TripDetailViewModel = viewModel(factory = detailFactory, key = "trip_detail_$tripId")
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    TripDetailScreen(
                        uiState = uiState,
                        onSaveTripDetails = viewModel::saveTripDetails,
                        onDeleteTrip = viewModel::deleteTrip,
                        onRenameSpot = viewModel::renameSpot,
                        onDeleteSpot = viewModel::deleteSpot,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.Settings.route) {
                    val viewModel: SettingsViewModel = viewModel(factory = factory)
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val offlineMaps: OfflineMapsViewModel = viewModel(factory = factory)
                    val offlineMapsState by offlineMaps.state.collectAsStateWithLifecycle()
                    val adPrivacyOptionsRequired by container.ads.privacyOptionsRequired.collectAsStateWithLifecycle()
                    SettingsScreen(
                        uiState = uiState,
                        onSetDailyGoal = viewModel::setDailyGoal,
                        onSetThresholds = viewModel::setThresholds,
                        onResetThresholds = viewModel::resetThresholds,
                        onSetKeepDeletedTripsInStats = viewModel::setKeepDeletedTripsInStats,
                        onGenerateSampleData = {
                            coroutineScope.launch { DebugDataSeeder.seed(container) }
                        },
                        onRunStepSourceCheck = viewModel::runStepSourceCheck,
                        onOpenHealthConnect = { onOpenHealthConnectPermissions(viewModel::refreshAvailability) },
                        onBack = { navController.popBackStack() },
                        // Only where Google's consent rules apply (see AdsManager.privacyOptionsRequired).
                        onOpenAdPrivacyOptions = onShowAdPrivacyOptions.takeIf { adPrivacyOptionsRequired },
                        offlineMaps = offlineMapsState,
                        offlineMapsActions = remember(offlineMaps) {
                            OfflineMapsActions(
                                onRetry = offlineMaps::retry,
                                onDownload = offlineMaps::download,
                                onCancelDownload = offlineMaps::cancelDownload,
                                onDelete = offlineMaps::delete,
                                onSetWifiOnly = offlineMaps::setWifiOnly,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Switches to a tab, keeping each tab's own state (scroll position, an open trip, the map's camera).
 * Settings closes first: otherwise it would be saved with the tab under it, and come back the next
 * time that tab is opened.
 */
private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.route == Screen.Settings.route) popBackStack()
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun StepSplitBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val highlighted = highlightedRoute(currentRoute, navController.previousBackStackEntry?.destination?.route)

    NavigationBar {
        bottomNavScreens.forEach { screen ->
            NavigationBarItem(
                selected = screen.isSelectedFor(highlighted),
                onClick = { navController.navigateToTab(screen.route) },
                icon = { Icon(imageVector = screen.icon(), contentDescription = null) },
                label = { Text(stringResource(screen.labelRes())) },
            )
        }
    }
}

@Composable
private fun Screen.icon(): ImageVector = when (this) {
    Screen.Today -> Icons.Filled.Home
    Screen.History -> Icons.Filled.DateRange
    Screen.Stats -> Icons.Filled.Star
    Screen.Trips -> Icons.Filled.Place
    Screen.OpenMap -> ImageVector.vectorResource(R.drawable.ic_map_24)
    Screen.Settings -> Icons.Filled.Settings
    is Screen.TripDetail -> Icons.Filled.Place
}

private fun Screen.labelRes(): Int = when (this) {
    Screen.Today -> R.string.nav_today
    Screen.History -> R.string.nav_history
    Screen.Stats -> R.string.nav_stats
    Screen.Trips -> R.string.nav_trips
    Screen.OpenMap -> R.string.nav_map
    Screen.Settings -> R.string.nav_settings
    is Screen.TripDetail -> R.string.nav_trips
}
