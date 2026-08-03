package com.example.stepsplit.ui.trips

import android.net.Uri
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
import com.example.stepsplit.domain.model.TripStepEstimate
import com.example.stepsplit.domain.trip.RoutePoint
import com.example.stepsplit.ui.common.formatClockTime
import com.example.stepsplit.ui.common.formatDateLabel
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

@Composable
fun TripDetailScreen(
    uiState: TripDetailUiState,
    onDeleteTrip: () -> Unit,
    onBuildGpxContent: suspend (String) -> String,
    onCreateGpxDocument: (suggestedFileName: String, onResult: (Uri?) -> Unit) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (uiState.deleted) {
        onBack()
        return
    }
    val trip = uiState.trip ?: return

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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow(stringResource(R.string.trip_detail_start_label), formatClockTime(trip.startEpochSecond))
                    DetailRow(
                        stringResource(R.string.trip_detail_end_label),
                        trip.endEpochSecond?.let { formatClockTime(it) } ?: "-",
                    )
                    DetailRow(stringResource(R.string.trip_detail_duration_label), formatElapsed(tripDurationSeconds(trip)))
                    DetailRow(stringResource(R.string.trip_detail_distance_label), formatDistance(trip.distanceMeters))
                    DetailRow(stringResource(R.string.trip_detail_steps_label), stepsText(uiState.estimatedSteps))
                }
            }
        }

        item {
            val routePoints = uiState.points.map { RoutePoint(it.latitude, it.longitude) }
            if (routePoints.isEmpty()) {
                Text(text = stringResource(R.string.trip_detail_route_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                RouteTraceCanvas(points = routePoints, modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val baseName = tripExportFileBaseName(trip)
                    onCreateGpxDocument("$baseName.gpx") { uri ->
                        if (uri == null) return@onCreateGpxDocument
                        coroutineScope.launch {
                            val content = onBuildGpxContent(baseName)
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(content.toByteArray(Charsets.UTF_8))
                            }
                        }
                    }
                }) { Text(stringResource(R.string.trip_detail_export_gpx_action)) }

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

@Composable
private fun stepsText(estimate: TripStepEstimate): String = when (estimate) {
    is TripStepEstimate.Available -> "${estimate.steps} ${stringResource(R.string.unit_steps)}"
    TripStepEstimate.Pending -> stringResource(R.string.trip_detail_steps_pending)
}

private fun tripDate(startEpochSecond: Long, startZoneId: String) =
    Instant.ofEpochSecond(startEpochSecond).atZone(ZoneId.of(startZoneId)).toLocalDate()

private fun tripDurationSeconds(trip: com.example.stepsplit.domain.model.TripSummary): Long {
    val end = trip.endEpochSecond ?: trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
    return (end - trip.startEpochSecond).coerceAtLeast(0L)
}
