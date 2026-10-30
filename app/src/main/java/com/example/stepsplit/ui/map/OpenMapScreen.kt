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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.R
import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.ui.common.ScreenHeader
import com.example.stepsplit.ui.trips.MetricScaleBar
import com.example.stepsplit.ui.trips.RouteCameraBounds
import com.example.stepsplit.ui.trips.SpotMarkerLayers
import com.example.stepsplit.ui.trips.appSettingsIntent
import com.example.stepsplit.ui.trips.formatDistance
import com.example.stepsplit.ui.trips.isSystemLocationEnabled
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
private const val HEADING_LAYER_ID = "you-are-here-heading"
private const val LOCATION_LAYER_PREFIX = "you-are-here"

/** Following the dot moves the map this smoothly to each new fix (they come about every 2 seconds). */
private val FOLLOW_ANIMATION = 600.milliseconds

/**
 * "Map turns with me" turns and moves the map this smoothly toward each new heading and fix: about
 * two thirds of the way in this time, frame by frame (see [OpenMapCamera.glideFraction]).
 */
private const val TURN_GLIDE_SECONDS = 0.18

/** Around the whole route; more at the bottom end, which the map's buttons cover. */
private val ROUTE_PADDING = PaddingValues(start = 40.dp, top = 40.dp, end = 72.dp, bottom = 64.dp)

/**
 * The Map tab: a full map with where the phone is, and the trip being recorded, if any.
 *
 * - **Your location.** A blue dot with a circle for its accuracy, a soft cone for the way the phone
 *   points (its compass - see [rememberDeviceHeading]), and a small arrow for the direction of travel
 *   while moving. The map follows it until the user pans, zooms or rotates; the My location button
 *   follows it again. While it follows, the screen stays on (it turns off as usual once the map stops
 *   following, or the screen closes). An old fix stays as a gray dot, with "Finding your location...".
 * - **Map turns with me.** The compass button turns the map with the phone, so what's ahead is up -
 *   with the GPS direction of travel instead while moving fast (see [MapBearing]); tapping it again
 *   turns the map back to north up. Turning the map by hand is off meanwhile.
 * - **The trip being recorded:** its route so far, its start and its spots, updating as it grows.
 * - **What can go wrong** is said above the map, with the way out: no location permission (Allow,
 *   or the app's settings once Android stops asking), location turned off (Turn on location), no
 *   fix yet, and a map that can't load - then the map draws a plain background
 *   ([MapStyles.offlineStyleJson]) with the dot and the route still on it, and Retry map tries the
 *   real map again. No internet alone doesn't do that: the outdoor style is inside the app, so it
 *   loads, with the map tiles viewed before (from MapLibre's cache) on its plain land color.
 * - **Offline maps.** Inside a downloaded pack's area the map is drawn from the pack, with no
 *   internet at all (see [rememberMapBackground]); the "no internet" banner then doesn't show. With
 *   no pack, a phone in Israel gets a one-time offer of the Israel map ([offerOfflineMap]).
 *
 * Location is asked for only while this tab is on screen, with the app in the foreground - switching
 * to another tab or app stops it at once (see [OpenMapViewModel]); so does the compass. The map is
 * the light outdoor style in dark mode too (see [rememberMapBackground]), and what's drawn on it keeps
 * its light-map colors; the buttons and banners follow the app's theme. The camera is kept for when the
 * tab comes back (`rememberCameraState` is saved with the tab's state). Approximate location is enough here (the circle is just bigger); recording a
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
    /** The gear icon in the header: opens Settings. */
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** No offline map is on the phone and the offer wasn't answered yet: offer the Israel map when the phone is there. */
    offerOfflineMap: Boolean = false,
    /** The offer was answered: [download] opens Settings, where the Offline maps are. */
    onAnswerOfflineMapOffer: (download: Boolean) -> Unit = {},
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
    var rotateWithMe by rememberSaveable { mutableStateOf(false) }
    var mapLoadFailed by rememberSaveable { mutableStateOf(false) }
    var drawingFromPack by remember { mutableStateOf(false) }
    val hasCompass = rememberHasCompass()

    // The screen stays on while the map follows the dot, as a navigation app's does.
    val view = LocalView.current
    val keepScreenOn = following && locationAvailable
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.open_map_title),
            subtitle = uiState.recording?.let { stringResource(R.string.open_map_recording_subtitle, formatDistance(it.distanceMeters)) },
            onOpenSettings = onOpenSettings,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )

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
        if (rotateWithMe && !hasCompass) StatusBanner(text = stringResource(R.string.open_map_no_compass_message))
        if (offerOfflineMap && location != null && OfflineMapOffer.isNear(location.longitude, location.latitude)) {
            StatusBanner(
                text = stringResource(R.string.open_map_offline_map_offer),
                actionLabel = stringResource(R.string.open_map_offline_map_offer_action),
                onAction = { onAnswerOfflineMapOffer(true) },
                dismissLabel = stringResource(R.string.open_map_offline_map_offer_dismiss),
                onDismiss = { onAnswerOfflineMapOffer(false) },
            )
        }
        if (mapLoadFailed && !drawingFromPack) {
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
                rotateWithMe = rotateWithMe,
                onRotateWithMeChange = { rotateWithMe = it },
                offline = mapLoadFailed,
                onLoadFailed = { mapLoadFailed = true },
                onDrawingFromPackChange = { drawingFromPack = it },
            )
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
    dismissLabel: String? = null,
    onDismiss: () -> Unit = {},
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
            if (dismissLabel != null) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            }
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
    rotateWithMe: Boolean,
    onRotateWithMeChange: (Boolean) -> Unit,
    offline: Boolean,
    onLoadFailed: () -> Unit,
    onDrawingFromPackChange: (Boolean) -> Unit,
) {
    val route = recording?.route.orEmpty()
    val cameraState = rememberCameraState(firstPosition = OpenMapCamera.initialPosition(location, route))
    val background = rememberMapBackground(cameraState, onlineFailed = offline)
    val currentOnDrawingFromPackChange by rememberUpdatedState(onDrawingFromPackChange)
    LaunchedEffect(background.fromPack) { currentOnDrawingFromPackChange(background.fromPack) }
    val coroutineScope = rememberCoroutineScope()
    val mapDescription = stringResource(if (recording != null) R.string.cd_open_map_with_recording else R.string.cd_open_map)

    // The compass runs while there's a dot to draw its cone on, or the map turns with it.
    val heading = rememberDeviceHeading(enabled = location != null || rotateWithMe, near = location)

    // Any pan, zoom or rotate by the user stops following.
    val currentOnFollowingChange by rememberUpdatedState(onFollowingChange)
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.moveReason }.collect { if (it == CameraMoveReason.GESTURE) currentOnFollowingChange(false) }
    }
    // The map's turn while following north up: as the user left it when they asked to follow, north
    // after "Back to north up".
    var followBearing by rememberSaveable { mutableDoubleStateOf(cameraState.position.bearing) }
    // While following (and the map doesn't turn with the phone), each new fix moves the map to it: at
    // once from far away (the first fix after the world view), smoothly otherwise.
    LaunchedEffect(following, location, rotateWithMe, followBearing) {
        if (!following || location == null || rotateWithMe) return@LaunchedEffect
        val current = cameraState.position
        val target = CameraPosition(
            target = Position(longitude = location.longitude, latitude = location.latitude),
            zoom = OpenMapCamera.followZoom(current.zoom, location),
            bearing = followBearing,
        )
        if (OpenMapCamera.isFarOut(current.zoom, location)) cameraState.position = target else cameraState.animateTo(target, FOLLOW_ANIMATION)
    }
    // Map turns with me: whenever the heading or the fix changes, the map glides toward the new turn
    // (and, while following, the new fix) frame by frame; a newer change takes over from where it is.
    val currentLocation by rememberUpdatedState(location)
    val currentFollowing by rememberUpdatedState(following)
    LaunchedEffect(rotateWithMe, cameraState) {
        if (!rotateWithMe) return@LaunchedEffect
        var source = MapBearing.Source.COMPASS
        snapshotFlow { Triple(heading.value, currentLocation, currentFollowing) }.collectLatest { (compass, fix, follow) ->
            val choice = MapBearing.choose(compass, fix, SystemClock.elapsedRealtimeNanos(), source)
            source = choice.source
            val goal = if (follow && fix != null) Position(longitude = fix.longitude, latitude = fix.latitude) else null
            if (fix != null && follow && OpenMapCamera.isFarOut(cameraState.position.zoom, fix)) {
                cameraState.position = CameraPosition(
                    target = goal!!,
                    zoom = OpenMapCamera.fittingZoom(fix),
                    bearing = choice.bearing?.toDouble() ?: cameraState.position.bearing,
                )
                return@collectLatest
            }
            cameraState.glideTo(bearing = choice.bearing, target = goal)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = mapDescription },
            baseStyle = background.style,
            cameraState = cameraState,
            options = MapOptions(
                // As on the trip screen: pan, zoom and rotate, no tilt; the app's own metric scale bar.
                // While the map turns with the phone, turning it by hand is off, and so is MapLibre's
                // compass: the app's compass button turns it back to north.
                gestureOptions = GestureOptions(isTiltEnabled = false, isRotateEnabled = !rotateWithMe),
                ornamentOptions = OrnamentOptions(isScaleBarEnabled = false, isCompassEnabled = !rotateWithMe),
            ),
            onMapLoadFailed = { onLoadFailed() },
        ) {
            val parts = recording?.parts
            if (route.size >= 2 && parts != null) {
                val routeSource = rememberGeoJsonSource(data = remember(parts) { parts.stretchesGeoJson() })
                LineLayer(
                    id = ROUTE_CASING_LAYER_ID,
                    source = routeSource,
                    color = const(OnMapColors.RouteCasing),
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
                        color = const(OnMapColors.Gap),
                        width = const(3.dp),
                        dasharray = const(listOf<Number>(2, 2)),
                    )
                }
            }
            route.firstOrNull()?.let { start ->
                val startSource = rememberGeoJsonSource(
                    data = remember(start) { GeoJsonData.Features(Point(longitude = start.longitude, latitude = start.latitude)) },
                )
                CircleLayer(id = START_LAYER_ID, source = startSource, color = const(OnMapColors.Start), radius = const(7.dp))
            }
            val spots = recording?.spots.orEmpty()
            // The numbers need the map's fonts: the offline background has none, a pack has them.
            if (spots.isNotEmpty()) SpotMarkerLayers(spots, showNumbers = !offline || background.fromPack)

            // The dot last, on top of everything, with the compass cone just under it.
            HeadingCone(
                id = HEADING_LAYER_ID,
                location = location,
                heading = { heading.value },
                color = if (fixIsFresh) FreshDotColor else StaleDotColor,
            )
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
                        // The whole route is shown north up.
                        onRotateWithMeChange(false)
                        onFollowingChange(false)
                        coroutineScope.launch { cameraState.showWholeRoute(OpenMapCamera.wholeRouteFit(route, location)) }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(painterResource(R.drawable.ic_fit_route_24), contentDescription = stringResource(R.string.trip_detail_map_show_route_action))
                }
            }
            val rotateLabel = stringResource(if (rotateWithMe) R.string.open_map_north_up_action else R.string.open_map_rotate_with_me_action)
            SmallFloatingActionButton(
                onClick = {
                    if (rotateWithMe) {
                        onRotateWithMeChange(false)
                        followBearing = 0.0
                        if (!following) coroutineScope.launch { cameraState.animateTo(cameraState.position.copy(bearing = 0.0)) }
                    } else {
                        onRotateWithMeChange(true)
                    }
                },
                modifier = Modifier.semantics { contentDescription = rotateLabel },
                containerColor = if (rotateWithMe) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ) {
                // Shows where north is while the map turns; plain north up otherwise.
                CompassNeedle(
                    bearing = { if (rotateWithMe) cameraState.position.bearing.toFloat() else 0f },
                    southColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallFloatingActionButton(
                onClick = {
                    followBearing = if (rotateWithMe) 0.0 else cameraState.position.bearing
                    onFollowingChange(true)
                },
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

/**
 * Glides the map toward [bearing] (null: keeps its turn) and [target] (null: stays where it is), a
 * little each frame, until it's there - or this is cancelled by a newer heading or fix, which then
 * glides on from wherever the map got to.
 */
private suspend fun CameraState.glideTo(bearing: Float?, target: Position?) {
    var lastFrame = withFrameNanos { it }
    while (true) {
        val frame = withFrameNanos { it }
        val fraction = OpenMapCamera.glideFraction((frame - lastFrame) / 1e9, TURN_GLIDE_SECONDS)
        lastFrame = frame
        val current = position
        val turnLeft = bearing?.let { HeadingMath.shortestTurn(current.bearing.toFloat(), it) } ?: 0f
        val turned = abs(turnLeft) < ARRIVED_DEGREES
        val nextTarget = OpenMapCamera.glideStep(current.target, target, fraction)
        position = current.copy(
            bearing = if (turned) bearing?.toDouble() ?: current.bearing else HeadingMath.normalize(current.bearing.toFloat() + turnLeft * fraction.toFloat()).toDouble(),
            target = nextTarget ?: current.target,
        )
        if (turned && nextTarget == target) return
    }
}

/** Closer than this, the glide just finishes the turn. */
private const val ARRIVED_DEGREES = 0.2f

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

/** Where the Map tab offers the Israel offline map: around Israel (a rough box; the pack's own area comes with the download). */
internal object OfflineMapOffer {
    fun isNear(longitude: Double, latitude: Double) = longitude in 34.2..35.95 && latitude in 29.45..33.4
}
