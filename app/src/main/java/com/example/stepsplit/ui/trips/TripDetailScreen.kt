package com.example.stepsplit.ui.trips

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
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
    // Bumped by the "Show whole route" button; TripRouteMapCard re-frames the route on each change.
    var showWholeRouteRequests by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val gpxShareFailureMessage = stringResource(R.string.trip_gpx_share_failure)
    val gpxShareChooserTitle = stringResource(R.string.trip_gpx_share_chooser_title)

    // A one-shot navigation side effect must never run directly during composition - LaunchedEffect
    // defers it to just after this composable enters composition/recomposes with deleted == true.
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onBack()
    }
    if (uiState.deleted) return
    val trip = uiState.trip ?: return
    val date = tripDate(trip.startEpochSecond, trip.startZoneId)
    val gpxShareSubject = stringResource(R.string.trip_gpx_share_subject, formatDateLabel(date))

    // Writes the GPX to the app's cache and opens the system share sheet on it, which also offers
    // "Save file" - see GpxShare. The points are snapshotted from uiState right here (on the main
    // thread, synchronously) before the coroutine starts, so a recomposition racing the background
    // work can never change what gets serialized; serialization and the file write run off the main
    // thread, with the coroutine scoped to this composable so leaving the screen cancels it safely
    // instead of touching a torn-down Toast/Context.
    val shareGpx: () -> Unit = {
        val points = uiState.points
        val tripId = trip.id
        val fileName = GpxExport.suggestedFileName(date)
        coroutineScope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    val gpx = GpxExport.toGpx(points)
                    withContext(Dispatchers.IO) { GpxShare.writeFile(context.cacheDir, tripId, fileName, gpx) }
                }.getOrNull()
            }
            val opened = file != null && runCatching {
                context.startActivity(GpxShare.chooserIntent(context, file, gpxShareSubject, gpxShareChooserTitle))
            }.isSuccess
            if (!opened) Toast.makeText(context, gpxShareFailureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    // A plain, non-scrolling Column on purpose. The route map is a native view: inside a vertically
    // scrollable container, any drag with a vertical component (an upward pan, a diagonal drag, a
    // pinch) is claimed by the container after a few pixels and cancelled for the map, so the map
    // stalls mid-gesture. Here nothing competes for touches on the map, and it takes whatever
    // height the details above and the buttons below leave free.
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
            }
            Text(text = formatDateLabel(date), style = MaterialTheme.typography.titleLarge)
        }

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

        if (uiState.points.isEmpty()) {
            Text(text = stringResource(R.string.trip_detail_route_empty), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
        } else {
            TripRouteMapCard(
                points = uiState.points,
                showWholeRouteRequests = showWholeRouteRequests,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }

        // Half the default side padding, so all three buttons fit on one line on a typical phone;
        // the row still wraps onto a second line rather than clipping on narrow screens, with large
        // font sizes, or with longer translations.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { showWholeRouteRequests++ },
                enabled = uiState.points.isNotEmpty(),
                contentPadding = CompactButtonPadding,
            ) {
                Text(stringResource(R.string.trip_detail_map_show_route_action))
            }
            OutlinedButton(onClick = shareGpx, enabled = uiState.points.isNotEmpty(), contentPadding = CompactButtonPadding) {
                Text(stringResource(R.string.trip_detail_share_gpx_action))
            }
            OutlinedButton(onClick = { showDeleteConfirm = true }, contentPadding = CompactButtonPadding) {
                Text(stringResource(R.string.trip_detail_delete_action))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.trip_detail_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (uiState.keepDeletedTripsInStats) {
                            R.string.trip_detail_delete_confirm_message_keep_stats
                        } else {
                            R.string.trip_detail_delete_confirm_message
                        },
                    ),
                )
            },
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

private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

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
