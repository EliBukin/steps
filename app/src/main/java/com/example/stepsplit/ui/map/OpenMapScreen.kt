package com.example.stepsplit.ui.map

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.ui.trips.MetricScaleBar
import com.example.stepsplit.ui.trips.RouteCameraBounds
import com.example.stepsplit.ui.trips.SpotMarkerLayers
import com.example.stepsplit.ui.trips.appSettingsIntent
import com.example.stepsplit.ui.trips.formatDistance
import com.example.stepsplit.ui.trips.isSystemLocationEnabled
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.location.BearingWithAccuracy
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationPuck
import org.maplibre.compose.location.LocationPuckColors
import org.maplibre.compose.location.PositionWithAccuracy
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

/** The trip being recorded: one strong color, unlike the trip screen's speed colors - it's still growing, and has no speed scale yet. */
private val RecordingRouteColor = Color(0xFFE8590C)

/** The same thin outline as the trip screen's route: dark on the light map, light on the dark one. */
private val LightStyleCasingColor = Color(0x99212121)
private val DarkStyleCasingColor = Color(0xB3FFFFFF)

/** A gap (see [splitAtGaps]): gray and dashed, as on the trip screen. */
private val LightStyleGapColor = Color(0xFF757575)
private val DarkStyleGapColor = Color(0xFFBDBDBD)

/** Matches the trip screen's start marker. */
private val StartMarkerColor = Color(0xFF2E7D32)

/** The "you are here" dot: blue while its fix is fresh, gray once it's old (see [remainingFreshMillis]). */
private val FreshDotColor = Color(0xFF1A73E8)
private val StaleDotColor = Color(0xFF8A8F94)
private val FreshPuckColors = LocationPuckColors(
    dotFillColorCurrentLocation = FreshDotColor,
    dotFillColorOldLocation = FreshDotColor,
    accuracyStrokeColor = FreshDotColor.copy(alpha = 0.5f),
    accuracyFillColor = FreshDotColor.copy(alpha = 0.15f),
    bearingColor = FreshDotColor,
)
private val StalePuckColors = LocationPuckColors(
    dotFillColorCurrentLocation = StaleDotColor,
    dotFillColorOldLocation = StaleDotColor,
    accuracyStrokeColor = StaleDotColor.copy(alpha = 0.5f),
    accuracyFillColor = StaleDotColor.copy(alpha = 0.15f),
    bearingColor = StaleDotColor,
)

private const val ROUTE_CASING_LAYER_ID = "recording-route-casing"
private const val ROUTE_LAYER_ID = "recording-route-line"
private const val GAP_LAYER_ID = "recording-route-gaps"
private const val START_LAYER_ID = "recording-route-start"
private const val LOCATION_LAYER_PREFIX = "you-are-here"

/** Following the dot moves the map this smoothly to each new fix (they come about every 2 seconds). */
private val FOLLOW_ANIMATION = 600.milliseconds

/** Around the whole route; more at the bottom end, which the map's buttons cover. */
private val ROUTE_PADDING = PaddingValues(start = 40.dp, top = 40.dp, end = 72.dp, bottom = 64.dp)

/**
 * The Open map screen: a full map with where the phone is, and the trip being recorded, if any.
 *
 * - **Your location.** A blue dot with a circle for its accuracy, and a small arrow for the
 *   direction of travel while moving. The map follows it until the user pans, zooms or rotates; the
 *   My location button follows it again. While it follows, the screen stays on (it turns off as
 *   usual once the map stops following, or the screen closes). An old fix stays as a gray dot, with
 *   "Finding your location...".
 * - **The trip being recorded:** its route so far, its start and its spots, updating as it grows.
 * - **What can go wrong** is said above the map, with the way out: no location permission (Allow,
 *   or the app's settings once Android stops asking), location turned off (Turn on location), no
 *   fix yet, and no internet - then the map draws a plain background ([MapStyles.offlineStyleJson])
 *   with the dot and the route still on it, and Retry map tries the real map again.
 *
 * Location is asked for only while this screen is on screen, with the app in the foreground - see
 * [OpenMapViewModel]. Approximate location is enough here (the circle is just bigger); recording a
 * trip still needs precise location, as before.
 *
 * Like the trip screen's map, the map must not go inside a vertically scrollable container.
 */
@Composable
fun OpenMapScreen(
    uiState: OpenMapUiState,
    location: MapFix?,
    /** Tells the ViewModel whether it may ask for location: permission granted, and location on. */
    onLocationAvailableChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    var locationOn by remember { mutableStateOf(isSystemLocationEnabled(context)) }
    // Android stops showing its permission dialog after the second "Don't allow"; from then on the
    // way in is the app's settings.
    var permissionDeniedForGood by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = hasLocationPermission(context)
        permissionDeniedForGood = !hasPermission && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Coming back from Android's settings (permission or location switched on there).
    LifecycleResumeEffect(context) {
        hasPermission = hasLocationPermission(context)
        locationOn = isSystemLocationEnabled(context)
        onPauseOrDispose {}
    }
    // Location switched on or off from the quick settings panel, which doesn't pause the app.
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                locationOn = isSystemLocationEnabled(context)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    val locationAvailable = hasPermission && locationOn
    LaunchedEffect(locationAvailable) { onLocationAvailableChange(locationAvailable) }

    // Whether the newest fix is still where the phone is: turns false by itself once it gets old.
    var fixIsFresh by remember { mutableStateOf(false) }
    LaunchedEffect(location) {
        val remaining = remainingFreshMillis(location, SystemClock.elapsedRealtimeNanos())
        fixIsFresh = remaining > 0
        if (remaining > 0) {
            delay(remaining)
            fixIsFresh = false
        }
    }

    var following by rememberSaveable { mutableStateOf(true) }
    var mapLoadFailed by rememberSaveable { mutableStateOf(false) }

    // The screen stays on while the map follows the dot, as a navigation app's does.
    val view = LocalView.current
    val keepScreenOn = following && locationAvailable
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OpenMapTopBar(uiState = uiState, onBack = onBack)

        when {
            !hasPermission -> if (permissionDeniedForGood) {
                StatusBanner(
                    text = stringResource(R.string.open_map_permission_settings_message),
                    actionLabel = stringResource(R.string.trip_permission_open_settings_action),
                    onAction = { context.startActivity(appSettingsIntent(context)) },
                )
            } else {
                StatusBanner(
                    text = stringResource(R.string.open_map_permission_message),
                    actionLabel = stringResource(R.string.open_map_permission_action),
                    onAction = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                )
            }
            !locationOn -> StatusBanner(
                text = stringResource(R.string.open_map_location_off_message),
                actionLabel = stringResource(R.string.trip_location_services_enable_action),
                onAction = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
            !fixIsFresh -> StatusBanner(text = stringResource(R.string.open_map_finding_location), showProgress = true)
        }
        if (mapLoadFailed) {
            StatusBanner(
                text = stringResource(R.string.open_map_offline_message),
                actionLabel = stringResource(R.string.trip_detail_map_retry_action),
                onAction = { mapLoadFailed = false },
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            OpenMapView(
                recording = uiState.recording,
                location = location,
                fixIsFresh = fixIsFresh,
                following = following,
                onFollowingChange = { following = it },
                offline = mapLoadFailed,
                onLoadFailed = { mapLoadFailed = true },
            )
        }
    }
}

@Composable
private fun OpenMapTopBar(uiState: OpenMapUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
        }
        Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(
                text = stringResource(R.string.open_map_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            uiState.recording?.let { recording ->
                Text(
                    text = stringResource(R.string.open_map_recording_subtitle, formatDistance(recording.distanceMeters)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One line above the map saying what's wrong, with the button that fixes it. */
@Composable
private fun StatusBanner(
    text: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    showProgress: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showProgress) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = if (actionLabel == null) 8.dp else 0.dp),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun OpenMapView(
    recording: RecordingOnMap?,
    location: MapFix?,
    fixIsFresh: Boolean,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    offline: Boolean,
    onLoadFailed: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val baseStyle = if (offline) {
        remember(isDark) { BaseStyle.Json(MapStyles.offlineStyleJson(isDark)) }
    } else {
        BaseStyle.Uri(MapStyles.styleUrl(isDark))
    }
    val route = recording?.route.orEmpty()
    val cameraState = rememberCameraState(firstPosition = OpenMapCamera.initialPosition(location, route))
    val coroutineScope = rememberCoroutineScope()
    val mapDescription = stringResource(if (recording != null) R.string.cd_open_map_with_recording else R.string.cd_open_map)

    // Any pan, zoom or rotate by the user stops following.
    val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.moveReason }.collect { if (it == CameraMoveReason.GESTURE) currentOnFollowingChange(false) }
    }
    // While following, each new fix moves the map to it: at once from far away (the first fix after
    // the world view), smoothly otherwise.
    LaunchedEffect(following, location) {
        if (!following || location == null) return@LaunchedEffect
        val current = cameraState.position
        val target = CameraPosition(
            target = Position(longitude = location.longitude, latitude = location.latitude),
            zoom = OpenMapCamera.followZoom(current.zoom, location),
            bearing = current.bearing,
        )
        if (OpenMapCamera.isFarOut(current.zoom, location)) cameraState.position = target else cameraState.animateTo(target, FOLLOW_ANIMATION)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
            baseStyle = baseStyle,
            cameraState = cameraState,
            options = MapOptions(
                // As on the trip screen: pan, zoom and rotate, no tilt; the app's own metric scale bar.
                gestureOptions = GestureOptions(isTiltEnabled = false),
                ornamentOptions = OrnamentOptions(isScaleBarEnabled = false),
            ),
            onMapLoadFailed = { onLoadFailed() },
        ) {
            val parts = recording?.parts
            if (route.size >= 2 && parts != null) {
                val routeSource = rememberGeoJsonSource(data = remember(parts) { parts.stretchesGeoJson() })
                LineLayer(
                    id = ROUTE_CASING_LAYER_ID,
                    source = routeSource,
                    color = const(if (isDark) DarkStyleCasingColor else LightStyleCasingColor),
                    width = const(6.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
                LineLayer(
                    id = ROUTE_LAYER_ID,
                    source = routeSource,
                    color = const(RecordingRouteColor),
                    width = const(4.dp),
                    cap = const(LineCap.Round),
                    join = const(LineJoin.Round),
                )
                if (parts.gaps.isNotEmpty()) {
                    val gapsSource = rememberGeoJsonSource(data = remember(parts) { parts.gapsGeoJson() })
                    LineLayer(
                        id = GAP_LAYER_ID,
                        source = gapsSource,
                        color = const(if (isDark) DarkStyleGapColor else LightStyleGapColor),
                        width = const(3.dp),
                        dasharray = const(listOf<Number>(2, 2)),
                    )
                }
            }
            route.firstOrNull()?.let { start ->
                val startSource = rememberGeoJsonSource(
                    data = remember(start) { GeoJsonData.Features(Point(longitude = start.longitude, latitude = start.latitude)) },
                )
                CircleLayer(id = START_LAYER_ID, source = startSource, color = const(StartMarkerColor), radius = const(7.dp))
            }
            val spots = recording?.spots.orEmpty()
            if (spots.isNotEmpty()) SpotMarkerLayers(spots, showNumbers = !offline)

            // The dot last, on top of everything.
            LocationPuck(
                idPrefix = LOCATION_LAYER_PREFIX,
                location = remember(location) { location?.toPuckLocation() },
                cameraState = cameraState,
                // Staleness is shown by the dot's color instead (see fixIsFresh), which also turns gray
                // while no new fix comes - the library would judge age only when a new fix arrives.
                oldLocationThreshold = Duration.INFINITE,
                accuracyThreshold = 0f,
                colors = if (fixIsFresh) FreshPuckColors else StalePuckColors,
                showBearingAccuracy = false,
            )
        }

        MetricScaleBar(
            metersPerDp = cameraState.metersPerDpAtTarget,
            maxWidth = maxWidth * 0.5f,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        )

        // Bottom end, above MapLibre's attribution button, as on the trip screen.
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (route.size >= 2) {
                SmallFloatingActionButton(
                    onClick = {
                        onFollowingChange(false)
                        coroutineScope.launch { cameraState.showWholeRoute(OpenMapCamera.wholeRouteFit(route, location)) }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(painterResource(R.drawable.ic_fit_route_24), contentDescription = stringResource(R.string.trip_detail_map_show_route_action))
                }
            }
            SmallFloatingActionButton(
                onClick = { onFollowingChange(true) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = if (following) FreshDotColor else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Icon(
                    painterResource(if (following) R.drawable.ic_my_location_24 else R.drawable.ic_location_searching_24),
                    contentDescription = stringResource(R.string.open_map_my_location_action),
                )
            }
        }
    }
}

private suspend fun CameraState.showWholeRoute(fit: RouteCameraBounds.Fit?) {
    when (fit) {
        is RouteCameraBounds.Fit.Bounded -> animateTo(boundingBox = fit.boundingBox, padding = ROUTE_PADDING)
        is RouteCameraBounds.Fit.Centered -> animateTo(CameraPosition(target = fit.target, zoom = fit.zoom))
        null -> {}
    }
}

/** Each recorded stretch with at least two points as a line; a lone point between two gaps draws nothing. */
private fun RouteParts.stretchesGeoJson() = GeoJsonData.Features(
    FeatureCollection(
        stretches.filter { it.size >= 2 }.map { stretch ->
            Feature(LineString(stretch.map { Position(longitude = it.longitude, latitude = it.latitude) }), null)
        },
    ),
)

private fun RouteParts.gapsGeoJson() = GeoJsonData.Features(
    FeatureCollection(
        gaps.map { (from, to) ->
            Feature(
                LineString(Position(longitude = from.longitude, latitude = from.latitude), Position(longitude = to.longitude, latitude = to.latitude)),
                null,
            )
        },
    ),
)

/** The map library's own location type, with the fix's age on the monotonic clock. */
private fun MapFix.toPuckLocation(): Location = Location(
    position = PositionWithAccuracy(Position(longitude = longitude, latitude = latitude), accuracyMeters?.toDouble()?.meters),
    course = bearingDegrees?.let { BearingWithAccuracy(Bearing.North + it.toDouble().degrees, accuracy = null) },
    timestamp = TimeSource.Monotonic.markNow() - (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos).coerceAtLeast(0L).nanoseconds,
)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
