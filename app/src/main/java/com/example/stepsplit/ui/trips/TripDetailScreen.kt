package com.example.stepsplit.ui.trips

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.ui.common.formatClockTimeInZone
import com.example.stepsplit.ui.common.formatDateLabel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripDetailScreen(
    uiState: TripDetailUiState,
    onDeleteTrip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val gpxExportSuccessMessage = stringResource(R.string.trip_gpx_export_success)
    val gpxExportFailureMessage = stringResource(R.string.trip_gpx_export_failure)

    // A one-shot navigation side effect must never run directly during composition - LaunchedEffect
    // defers it to just after this composable enters composition/recomposes with deleted == true.
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }
    if (uiState.deleted) return
    val trip = uiState.trip ?: return

    // Neither requests storage permission nor needs a FileProvider - the system document picker
    // hands back a content:// Uri this app can write to directly via ContentResolver. The points are
    // snapshotted from uiState right here (on the main thread, synchronously) before the coroutine
    // starts, so a recomposition racing the background work can never change what gets serialized;
    // serialization and the ContentResolver write both then run on Dispatchers.Default/IO, off the
    // main thread, with the coroutine scoped to this composable so leaving the screen cancels it
    // safely instead of touching a torn-down Toast/Context.
    val gpxExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val points = uiState.points
        coroutineScope.launch {
            val succeeded = withContext(Dispatchers.Default) {
                runCatching {
                    val gpx = GpxExport.toGpx(points)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(gpx.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open an output stream for $uri")
                    }
                }.isSuccess
            }
            Toast.makeText(context, if (succeeded) gpxExportSuccessMessage else gpxExportFailureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
                }
                Text(text = formatDateLabel(tripDate(trip.startEpochSecond, trip.startZoneId)), style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            val zoneId = ZoneId.of(trip.startZoneId)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow(stringResource(R.string.trip_detail_start_label), formatClockTimeInZone(trip.startEpochSecond, zoneId))
                    DetailRow(
                        stringResource(R.string.trip_detail_end_label),
                        trip.endEpochSecond?.let { formatClockTimeInZone(it, zoneId) } ?: "-",
                    )
                    DetailRow(stringResource(R.string.trip_detail_duration_label), formatElapsed(tripDurationSeconds(trip)))
                    DetailRow(stringResource(R.string.trip_detail_distance_label), formatDistance(trip.distanceMeters))
                }
            }
        }

        item {
            if (uiState.points.isEmpty()) {
                Text(text = stringResource(R.string.trip_detail_route_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                TripRouteMapCard(points = uiState.points, modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { gpxExportLauncher.launch(GpxExport.suggestedFileName(tripDate(trip.startEpochSecond, trip.startZoneId))) },
                    enabled = uiState.points.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.trip_detail_export_gpx_action))
                }
                OutlinedButton(onClick = { showDeleteConfirm = true }) {
                    Text(stringResource(R.string.trip_detail_delete_action))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.trip_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.trip_detail_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteTrip()
                }) { Text(stringResource(R.string.trip_detail_delete_confirm_action)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun tripDate(startEpochSecond: Long, startZoneId: String) =
    Instant.ofEpochSecond(startEpochSecond).atZone(ZoneId.of(startZoneId)).toLocalDate()

private fun tripDurationSeconds(trip: com.example.stepsplit.domain.model.TripSummary): Long {
    val end = trip.endEpochSecond ?: trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
    return (end - trip.startEpochSecond).coerceAtLeast(0L)
}
