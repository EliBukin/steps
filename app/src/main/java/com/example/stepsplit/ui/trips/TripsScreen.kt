package com.example.stepsplit.ui.trips

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.stepsplit.R
import com.example.stepsplit.data.trip.SpotMarkResult
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.trip.service.TripRecordingService
import com.example.stepsplit.ui.common.ScreenHeader
import com.example.stepsplit.ui.common.formatClockTimeInZone
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.common.formatDayLabel
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What the current permission/location-enabled flow will do once it succeeds - Start and Resume share every dialog/check below, differing only in which command they ultimately send. */
private sealed interface PendingRecordingAction {
    data object Start : PendingRecordingAction
    data class Resume(val tripId: Long) : PendingRecordingAction
}

@Composable
fun TripsScreen(
    uiState: TripsUiState,
    onOpenTrip: (Long) -> Unit,
    onFinishInterruptedTripAtLastPoint: (Long) -> Unit,
    onRequestTripPermissions: (onResult: (Map<String, Boolean>) -> Unit) -> Unit,
    onMarkSpot: () -> Unit,
    /** Each Mark spot outcome, once - shown as a snackbar. */
    spotMarkResults: Flow<SpotMarkResult>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }
    // A newer outcome replaces the one showing, so "Spot 4 marked" never waits behind "Spot 3 marked".
    LaunchedEffect(spotMarkResults) {
        spotMarkResults.collect { result ->
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(spotMarkMessage(resources, result)) }
        }
    }

    var pendingAction by remember { mutableStateOf<PendingRecordingAction?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var showPreciseRequired by remember { mutableStateOf(false) }
    var showLocationDisabled by remember { mutableStateOf(false) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    var showFinishConfirm by remember { mutableStateOf(false) }

    fun sendCommand(action: PendingRecordingAction) {
        when (action) {
            PendingRecordingAction.Start -> startTripService(context)
            is PendingRecordingAction.Resume -> resumeTripService(context, action.tripId)
        }
    }

    fun attemptRecording(action: PendingRecordingAction) {
        if (isSystemLocationEnabled(context)) {
            sendCommand(action)
        } else {
            showLocationDisabled = true
        }
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionDenied = result[Manifest.permission.POST_NOTIFICATIONS] != true
        }
        val action = pendingAction ?: return
        if (fineGranted) attemptRecording(action) else showPreciseRequired = true
    }

    // Both Start and Resume always go through this same rationale-then-request flow - a trip can
    // only ever reach INTERRUPTED after fine location was already granted once, but it may have
    // been revoked since, so Resume re-validates exactly like a fresh Start rather than assuming.
    fun beginRecording(action: PendingRecordingAction) {
        pendingAction = action
        showRationale = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenHeader(title = stringResource(R.string.trips_title)) }

            uiState.interruptedTrip?.let { trip ->
                item {
                    InterruptedTripCard(
                        onResume = { beginRecording(PendingRecordingAction.Resume(trip.id)) },
                        onFinishAtLastPoint = { onFinishInterruptedTripAtLastPoint(trip.id) },
                    )
                }
            }

            if (uiState.activeTrip != null) {
                item {
                    ActiveRecordingCard(
                        uiState = uiState,
                        notificationPermissionDenied = notificationPermissionDenied,
                        onMarkSpotClick = onMarkSpot,
                        onFinishClick = { showFinishConfirm = true },
                    )
                }
            } else if (uiState.interruptedTrip == null) {
                item {
                    IdleStatusCard(onStartClick = { beginRecording(PendingRecordingAction.Start) })
                }
            }

            item { Text(text = stringResource(R.string.trips_history_title), style = MaterialTheme.typography.titleMedium) }

            if (uiState.history.isEmpty()) {
                item { Text(text = stringResource(R.string.trips_history_empty), style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(uiState.history, key = { it.summary.id }) { trip ->
                    TripHistoryCard(trip = trip, onClick = { onOpenTrip(trip.summary.id) })
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false; pendingAction = null },
            title = { Text(stringResource(R.string.trip_permission_rationale_title)) },
            text = { Text(stringResource(R.string.trip_permission_rationale_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    onRequestTripPermissions(::onPermissionResult)
                }) { Text(stringResource(R.string.trip_permission_rationale_continue)) }
            },
            dismissButton = { TextButton(onClick = { showRationale = false; pendingAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showPreciseRequired) {
        AlertDialog(
            onDismissRequest = { showPreciseRequired = false; pendingAction = null },
            title = { Text(stringResource(R.string.trip_permission_precise_required_title)) },
            text = { Text(stringResource(R.string.trip_permission_denied_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showPreciseRequired = false
                    pendingAction = null
                    context.startActivity(appSettingsIntent(context))
                }) { Text(stringResource(R.string.trip_permission_open_settings_action)) }
            },
            dismissButton = { TextButton(onClick = { showPreciseRequired = false; pendingAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showLocationDisabled) {
        AlertDialog(
            onDismissRequest = { showLocationDisabled = false; pendingAction = null },
            title = { Text(stringResource(R.string.trip_permission_rationale_title)) },
            text = { Text(stringResource(R.string.trip_location_services_disabled_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDisabled = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text(stringResource(R.string.trip_location_services_enable_action)) }
            },
            dismissButton = { TextButton(onClick = { showLocationDisabled = false; pendingAction = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showFinishConfirm) {
        AlertDialog(
            onDismissRequest = { showFinishConfirm = false },
            title = { Text(stringResource(R.string.trip_finish_confirm_title)) },
            text = { Text(stringResource(R.string.trip_finish_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showFinishConfirm = false
                    sendFinishCommand(context)
                }) { Text(stringResource(R.string.trip_finish_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { showFinishConfirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun IdleStatusCard(onStartClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.trips_status_off_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.trips_status_off_description), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onStartClick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.trips_start_button))
            }
        }
    }
}

/**
 * The live trip: a calm card (recording is the normal, wanted state - the red error colors are kept
 * for an interrupted trip), a blinking dot, the GPS signal, and a clock that ticks every second.
 *
 * Mark spot and Finish sit side by side, tall enough to hit in a moving jeep. Mark spot is the
 * filled one: it is tapped again and again during a trip, Finish once (and asks first). Mark spot
 * saves at once, with no dialog, and is disabled only while a tap is still waiting for its fix.
 */
@Composable
private fun ActiveRecordingCard(
    uiState: TripsUiState,
    notificationPermissionDenied: Boolean,
    onMarkSpotClick: () -> Unit,
    onFinishClick: () -> Unit,
) {
    val recordingActiveDescription = stringResource(R.string.cd_trip_recording_active)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = recordingActiveDescription },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.trip_recording_indicator),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                GpsStatusChip(uiState.gpsStatus)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column {
                    Text(stringResource(R.string.trip_elapsed_label), style = MaterialTheme.typography.labelMedium)
                    Text(formatElapsedClock(uiState.elapsedSeconds), style = MaterialTheme.typography.headlineMedium)
                }
                Column {
                    Text(stringResource(R.string.trip_distance_label), style = MaterialTheme.typography.labelMedium)
                    Text(formatDistance(uiState.activeTrip?.distanceMeters ?: 0.0), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text(
                text = stringResource(R.string.trip_points_count_label, uiState.acceptedPointCount),
                style = MaterialTheme.typography.bodySmall,
            )
            if (uiState.spotCount > 0) {
                Text(text = stringResource(R.string.trip_spots_count_label, uiState.spotCount), style = MaterialTheme.typography.bodySmall)
            }
            Text(text = stringResource(R.string.trip_screen_off_message), style = MaterialTheme.typography.bodySmall)
            if (notificationPermissionDenied) {
                Text(
                    text = stringResource(R.string.trip_notification_permission_denied_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onMarkSpotClick,
                    enabled = !uiState.isMarkingSpot,
                    modifier = Modifier.weight(1f).heightIn(min = RECORDING_BUTTON_HEIGHT),
                ) {
                    if (uiState.isMarkingSpot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(painterResource(R.drawable.ic_mark_spot_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.trip_mark_spot_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onFinishClick, modifier = Modifier.weight(1f).heightIn(min = RECORDING_BUTTON_HEIGHT)) {
                    Icon(painterResource(R.drawable.ic_stop_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.trip_finish_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Taller than a usual button: Mark spot gets tapped in a moving jeep. */
private val RECORDING_BUTTON_HEIGHT = 56.dp

/**
 * A red dot that slowly fades in and out while a trip records. It fades *up* to fully red: with
 * animations turned off on the phone, an infinite animation rests on its target value, and a solid
 * dot reads better than a faint one.
 */
@Composable
private fun RecordingDot() {
    val dotAlpha by rememberInfiniteTransition(label = "recording-dot").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "recording-dot-alpha",
    )
    val color = MaterialTheme.colorScheme.error
    Box(
        modifier = Modifier
            .size(12.dp)
            .graphicsLayer { alpha = dotAlpha }
            .background(color, CircleShape),
    )
}

/** "GPS: Good" with signal bars: none lit while searching, two when weak, all four when good. */
@Composable
private fun GpsStatusChip(status: GpsStatus) {
    val litBars = when (status) {
        GpsStatus.SEARCHING -> 0
        GpsStatus.WEAK -> 2
        GpsStatus.GOOD -> 4
    }
    val litColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
                val barWidth = size.width / 7
                for (i in 0 until 4) {
                    val barHeight = size.height * (i + 1) / 4
                    drawRect(
                        color = if (i < litBars) litColor else dimColor,
                        topLeft = Offset(x = i * 2 * barWidth, y = size.height - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }
            }
            Text(
                text = "${stringResource(R.string.trip_gps_status_label)}: ${gpsStatusText(status)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun InterruptedTripCard(onResume: () -> Unit, onFinishAtLastPoint: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.trip_interrupted_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.trip_interrupted_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text(stringResource(R.string.trip_interrupted_resume_action)) }
                TextButton(onClick = onFinishAtLastPoint) { Text(stringResource(R.string.trip_interrupted_finish_action)) }
            }
        }
    }
}

/**
 * One finished trip: a small picture of its route, then its type icon and its name, or its day,
 * date and start time when it has no name (a named trip shows those under the name), then its
 * distance and duration.
 */
@Composable
private fun TripHistoryCard(trip: FinishedTrip, onClick: () -> Unit) {
    val summary = trip.summary
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RouteThumbnail(points = trip.routePreview)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val date = tripStartDate(summary)
                val dateLine = stringResource(
                    R.string.trip_card_title_format,
                    formatDayLabel(date),
                    formatDateLabel(date),
                    formatClockTimeInZone(summary.startEpochSecond, ZoneId.of(summary.startZoneId)),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TripTypeIcon(trip.type, size = 18.dp)
                    Text(
                        text = summary.name ?: dateLine,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (summary.name != null) {
                    Text(text = dateLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = stringResource(
                        R.string.trip_card_distance_duration_format,
                        formatDistance(summary.distanceMeters),
                        formatElapsed(tripDurationSeconds(summary)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun tripStartDate(trip: TripSummary) =
    Instant.ofEpochSecond(trip.startEpochSecond).atZone(ZoneId.of(trip.startZoneId)).toLocalDate()

private fun tripDurationSeconds(trip: TripSummary): Long {
    val end = trip.endEpochSecond ?: trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
    return (end - trip.startEpochSecond).coerceAtLeast(0L)
}

@Composable
private fun gpsStatusText(status: GpsStatus): String = when (status) {
    GpsStatus.SEARCHING -> stringResource(R.string.trip_gps_status_searching)
    GpsStatus.WEAK -> stringResource(R.string.trip_gps_status_weak)
    GpsStatus.GOOD -> stringResource(R.string.trip_gps_status_good)
}

@Composable
internal fun formatDistance(meters: Double): String = if (meters >= 1000.0) {
    stringResource(R.string.trip_unit_kilometers_format, meters / 1000.0)
} else {
    stringResource(R.string.trip_unit_meters_format, meters.toInt())
}

/** A running clock for the trip being recorded, "h:mm:ss" - e.g. "0:42:17". */
internal fun formatElapsedClock(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0L)
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}

@Composable
internal fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) {
        stringResource(R.string.trip_duration_hours_minutes_format, hours.toInt(), minutes.toInt())
    } else {
        stringResource(R.string.trip_duration_minutes_format, minutes.toInt())
    }
}

private fun isSystemLocationEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return LocationManagerCompat.isLocationEnabled(manager)
}

private fun startTripService(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TripRecordingService::class.java).setAction(TripRecordingService.ACTION_START),
    )
}

/**
 * Sent synchronously, directly from the Resume button's click handler - not routed through a
 * ViewModel/coroutine - so the command reaches the service even if the activity is backgrounded
 * immediately afterward. The service (see `TripRecordingCommandController.handleResume`) is what
 * atomically verifies the trip is still INTERRUPTED and transitions it to ACTIVE; this call never
 * touches Room directly.
 */
private fun resumeTripService(context: Context, tripId: Long) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TripRecordingService::class.java)
            .setAction(TripRecordingService.ACTION_RESUME)
            .putExtra(TripRecordingService.EXTRA_TRIP_ID, tripId),
    )
}

private fun sendFinishCommand(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, TripRecordingService::class.java).setAction(TripRecordingService.ACTION_FINISH),
    )
}

private fun appSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)
