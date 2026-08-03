package com.example.stepsplit.ui.trips

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.trip.service.TripRecordingService
import com.example.stepsplit.ui.common.formatDateLabel
import java.time.Instant
import java.time.ZoneId

@Composable
fun TripsScreen(
    uiState: TripsUiState,
    onOpenTrip: (Long) -> Unit,
    onResumeInterruptedTrip: (Long, () -> Unit) -> Unit,
    onFinishInterruptedTripAtLastPoint: (Long) -> Unit,
    onRequestTripPermissions: (onResult: (Map<String, Boolean>) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var showRationale by remember { mutableStateOf(false) }
    var showApproximateWarning by remember { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }
    var showLocationDisabled by remember { mutableStateOf(false) }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    var showFinishConfirm by remember { mutableStateOf(false) }

    fun attemptStart() {
        if (isSystemLocationEnabled(context)) {
            startTripService(context)
        } else {
            showLocationDisabled = true
        }
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val fineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionDenied = result[Manifest.permission.POST_NOTIFICATIONS] != true
        }
        when {
            fineGranted -> attemptStart()
            coarseGranted -> showApproximateWarning = true
            else -> showDenied = true
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = stringResource(R.string.trips_title), style = MaterialTheme.typography.titleLarge) }

        uiState.interruptedTrip?.let { trip ->
            item {
                InterruptedTripCard(
                    onResume = { onResumeInterruptedTrip(trip.id) { startTripService(context) } },
                    onFinishAtLastPoint = { onFinishInterruptedTripAtLastPoint(trip.id) },
                )
            }
        }

        if (uiState.activeTrip != null) {
            item {
                ActiveRecordingCard(
                    uiState = uiState,
                    notificationPermissionDenied = notificationPermissionDenied,
                    onFinishClick = { showFinishConfirm = true },
                )
            }
        } else if (uiState.interruptedTrip == null) {
            item {
                IdleStatusCard(onStartClick = { showRationale = true })
            }
        }

        item { Text(text = stringResource(R.string.trips_history_title), style = MaterialTheme.typography.titleMedium) }

        if (uiState.history.isEmpty()) {
            item { Text(text = stringResource(R.string.trips_history_empty), style = MaterialTheme.typography.bodyMedium) }
        } else {
            items(uiState.history, key = { it.id }) { trip ->
                TripHistoryCard(trip = trip, onClick = { onOpenTrip(trip.id) })
            }
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text(stringResource(R.string.trip_permission_rationale_title)) },
            text = { Text(stringResource(R.string.trip_permission_rationale_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    onRequestTripPermissions(::onPermissionResult)
                }) { Text(stringResource(R.string.trip_permission_rationale_continue)) }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showApproximateWarning) {
        AlertDialog(
            onDismissRequest = { showApproximateWarning = false },
            title = { Text(stringResource(R.string.trip_permission_approximate_title)) },
            text = { Text(stringResource(R.string.trip_permission_approximate_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showApproximateWarning = false
                    attemptStart()
                }) { Text(stringResource(R.string.trip_permission_approximate_continue)) }
            },
            dismissButton = { TextButton(onClick = { showApproximateWarning = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showDenied) {
        AlertDialog(
            onDismissRequest = { showDenied = false },
            title = { Text(stringResource(R.string.trip_permission_rationale_title)) },
            text = { Text(stringResource(R.string.trip_permission_denied_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDenied = false
                    context.startActivity(appSettingsIntent(context))
                }) { Text(stringResource(R.string.trip_permission_open_settings_action)) }
            },
            dismissButton = { TextButton(onClick = { showDenied = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (showLocationDisabled) {
        AlertDialog(
            onDismissRequest = { showLocationDisabled = false },
            title = { Text(stringResource(R.string.trip_permission_rationale_title)) },
            text = { Text(stringResource(R.string.trip_location_services_disabled_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationDisabled = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) { Text(stringResource(R.string.trip_location_services_enable_action)) }
            },
            dismissButton = { TextButton(onClick = { showLocationDisabled = false }) { Text(stringResource(android.R.string.cancel)) } },
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
                Text(stringResource(R.string.trips_start_button))
            }
        }
    }
}

@Composable
private fun ActiveRecordingCard(
    uiState: TripsUiState,
    notificationPermissionDenied: Boolean,
    onFinishClick: () -> Unit,
) {
    val recordingActiveDescription = stringResource(R.string.cd_trip_recording_active)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = recordingActiveDescription },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.trip_recording_indicator),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(stringResource(R.string.trip_elapsed_label), style = MaterialTheme.typography.labelMedium)
                    Text(formatElapsed(uiState.elapsedSeconds), style = MaterialTheme.typography.headlineSmall)
                }
                Column {
                    Text(stringResource(R.string.trip_distance_label), style = MaterialTheme.typography.labelMedium)
                    Text(formatDistance(uiState.activeTrip?.distanceMeters ?: 0.0), style = MaterialTheme.typography.headlineSmall)
                }
            }
            Text(
                text = "${stringResource(R.string.trip_gps_status_label)}: ${gpsStatusText(uiState.gpsStatus)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.trip_points_count_label, uiState.acceptedPointCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(text = stringResource(R.string.trip_screen_off_message), style = MaterialTheme.typography.bodySmall)
            if (notificationPermissionDenied) {
                Text(
                    text = stringResource(R.string.trip_notification_permission_denied_notice),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onFinishClick, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trip_finish_button))
            }
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

@Composable
private fun TripHistoryCard(trip: TripSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = formatDateLabel(tripStartDate(trip)), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    R.string.trip_card_distance_duration_format,
                    formatDistance(trip.distanceMeters),
                    formatElapsed(tripDurationSeconds(trip)),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
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
