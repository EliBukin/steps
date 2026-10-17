package com.example.stepsplit.ui.trips

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.model.TripType
import com.example.stepsplit.domain.trip.TripElevation
import com.example.stepsplit.domain.trip.TripMotion
import com.example.stepsplit.domain.trip.TripName
import com.example.stepsplit.ui.common.formatClockTimeInZone
import com.example.stepsplit.ui.common.formatDateLabel
import com.example.stepsplit.ui.common.formatDayLabel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripDetailScreen(
    uiState: TripDetailUiState,
    onSaveTripDetails: (name: String, type: TripType) -> Unit,
    onDeleteTrip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
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
    val dateTitle = "${formatDayLabel(date)} ${formatDateLabel(date)}"
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
        val tripName = trip.name
        val fileName = GpxExport.suggestedFileName(date)
        coroutineScope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    val gpx = GpxExport.toGpx(points, name = tripName)
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
    // height the title and summary above and the height card and Share button below leave free.
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
            }
            TripTypeIcon(uiState.type, size = 24.dp, modifier = Modifier.padding(end = 8.dp))
            // An unnamed trip is titled by its date, as it always was; a named one shows the date under its name.
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text(
                    text = trip.name ?: dateTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trip.name != null) {
                    Text(text = dateTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { showEdit = true }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.trip_detail_edit_action))
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.trip_detail_delete_action))
            }
        }

        TripSummaryCard(trip, uiState.motion, uiState.elevation)

        if (uiState.points.isEmpty()) {
            Text(text = stringResource(R.string.trip_detail_route_empty), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.weight(1f))
        } else {
            // The map and the height chart under it share the height that is left. The chart shows
            // only while the map keeps at least MIN_MAP_HEIGHT next to it: on a short phone the map
            // wins, and the climb and descent stay in the card above. No chart at all when too few
            // points have a height - see TripElevation.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val elevation = uiState.elevation
                val showChart = elevation != null && maxHeight >= MIN_MAP_HEIGHT + SECTION_SPACING + TripHeightChartHeight
                Column(verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)) {
                    TripRouteMapCard(
                        points = uiState.points,
                        speedColoring = uiState.speedColoring,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    if (showChart && elevation != null) {
                        TripHeightChartCard(elevation = elevation, aboveSeaLevel = uiState.heightsAboveSeaLevel)
                    }
                }
            }
        }

        Button(onClick = shareGpx, enabled = uiState.points.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.trip_detail_share_gpx_action))
        }
    }

    if (showEdit) {
        EditTripDialog(
            initialName = trip.name.orEmpty(),
            initialType = uiState.type,
            suggestedType = uiState.suggestedType,
            namePlaceholder = dateTitle,
            onSave = { name, type ->
                showEdit = false
                onSaveTripDetails(name, type)
            },
            onDismiss = { showEdit = false },
        )
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

/**
 * The trip's numbers above the map, in two rows: when it ran, how long it took and how far it went;
 * then the climb and descent under the time, the moving time (see [TripMotion]) under the duration,
 * and the top speed under the distance. They are laid out as a table ([SummaryGrid]), so the second
 * row lines up with the first even when the time range wraps onto a second line on a narrow phone.
 * [motion] is null when the route has fewer than two points, and [elevation] when too few points
 * have a height; their cells are then left empty, and the second row is left out when it would be
 * empty. Every row takes its height from the map.
 */
@Composable
private fun TripSummaryCard(trip: TripSummary, motion: TripMotion.Motion?, elevation: TripElevation.Elevation?) {
    val zoneId = ZoneId.of(trip.startZoneId)
    val locale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        SummaryGrid(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            SummaryCell(
                label = stringResource(R.string.trip_detail_time_label),
                value = stringResource(
                    R.string.trip_detail_time_range_format,
                    formatClockTimeInZone(trip.startEpochSecond, zoneId),
                    trip.endEpochSecond?.let { formatClockTimeInZone(it, zoneId) } ?: "-",
                ),
                maxLines = 2,
            )
            SummaryCell(stringResource(R.string.trip_detail_duration_label), formatElapsed(tripDurationSeconds(trip)))
            SummaryCell(stringResource(R.string.trip_detail_distance_label), formatDistance(trip.distanceMeters))
            if (motion != null || elevation != null) {
                if (elevation != null) {
                    val climb = formatHeightNumber(elevation.climbMeters, locale)
                    val descent = formatHeightNumber(elevation.descentMeters, locale)
                    SummaryCell(
                        label = stringResource(R.string.trip_detail_climb_descent_label),
                        value = stringResource(R.string.trip_detail_climb_descent_format, climb, descent),
                        valueDescription = stringResource(
                            R.string.cd_trip_climb_descent,
                            stringResource(R.string.trip_height_meters_format, climb),
                            stringResource(R.string.trip_height_meters_format, descent),
                        ),
                    )
                } else {
                    Spacer(Modifier)
                }
                if (motion != null) {
                    SummaryCell(stringResource(R.string.trip_detail_moving_time_label), formatElapsed(motion.movingSeconds))
                    SummaryCell(
                        label = stringResource(R.string.trip_detail_top_speed_label),
                        // Formatted like the speed legend's ends, so the two always agree on the map's scale.
                        value = motion.topSpeedMetersPerSecond?.let {
                            stringResource(R.string.trip_map_speed_format, formatLegendSpeed(it * METERS_PER_SECOND_TO_KMH, locale))
                        } ?: "–",
                    )
                }
            }
        }
    }
}

/**
 * Lays its cells out in rows of three, like a table. The second and third columns are as wide as
 * their widest cell; the first takes what is left, so a long time range wraps instead of running
 * into the next column. The columns are spread across the width with at least [columnSpacing]
 * between them, and each row is as tall as its tallest cell, so the rows always line up. Mirrors in
 * right-to-left languages.
 */
@Composable
private fun SummaryGrid(
    modifier: Modifier = Modifier,
    rowSpacing: Dp = 12.dp,
    columnSpacing: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val columns = 3
        val rows = (measurables.size + columns - 1) / columns
        fun widest(column: Int) = (column until measurables.size step columns).maxOfOrNull { measurables[it].maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val width = constraints.maxWidth
        val gap = columnSpacing.roundToPx()
        val widths = IntArray(columns) { if (it == 0) 0 else widest(it) }
        widths[0] = min(widest(0), (width - widths[1] - widths[2] - 2 * gap).coerceAtLeast(0))
        val placeables = measurables.mapIndexed { i, measurable -> measurable.measure(Constraints(maxWidth = widths[i % columns])) }
        val rowHeights = IntArray(rows) { row -> (0 until columns).maxOf { placeables.getOrNull(row * columns + it)?.height ?: 0 } }
        val rowGap = rowSpacing.roundToPx()
        val free = width - widths.sum()
        val x = intArrayOf(0, widths[0] + free / 2, width - widths[2])
        layout(width, rowHeights.sum() + rowGap * (rows - 1).coerceAtLeast(0)) {
            var y = 0
            for (row in 0 until rows) {
                for (column in 0 until columns) placeables.getOrNull(row * columns + column)?.placeRelative(x[column], y)
                y += rowHeights[row] + rowGap
            }
        }
    }
}

/**
 * The Edit trip dialog: an optional name (up to [TripName.MAX_LENGTH] characters; empty shows the
 * date, given as [namePlaceholder]) and Walk or Drive, with the automatic suggestion marked.
 * [onSave] gets the name as typed; the repository trims it.
 */
@Composable
private fun EditTripDialog(
    initialName: String,
    initialType: TripType,
    suggestedType: TripType,
    namePlaceholder: String,
    onSave: (name: String, type: TripType) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = TripName.limit(it) },
                    label = { Text(stringResource(R.string.trip_edit_name_label)) },
                    placeholder = { Text(namePlaceholder) },
                    supportingText = {
                        Text(text = "${name.length}/${TripName.MAX_LENGTH}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = stringResource(R.string.trip_edit_type_label), style = MaterialTheme.typography.titleSmall)
                Column(modifier = Modifier.selectableGroup()) {
                    TripType.entries.forEach { option ->
                        TripTypeOption(
                            type = option,
                            selected = option == type,
                            suggested = option == suggestedType,
                            onSelect = { type = option },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.trip_edit_type_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, type) }) { Text(stringResource(R.string.trip_edit_save_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** One radio row of the dialog's type choice: the button, the type's icon and name, and a "Suggested" tag on the automatic one. */
@Composable
private fun TripTypeOption(type: TripType, selected: Boolean, suggested: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        TripTypeIcon(type, size = 24.dp, contentDescription = null)
        Text(text = stringResource(tripTypeLabelRes(type)), style = MaterialTheme.typography.bodyLarge)
        if (suggested) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    text = stringResource(R.string.trip_edit_type_suggested),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * One label-over-value cell of the summary card: time, climb and descent, duration, moving time,
 * distance or top speed. [valueDescription], when given, is what TalkBack reads instead of [value]
 * (for "↑230 ↓222 m").
 */
@Composable
private fun SummaryCell(label: String, value: String, maxLines: Int = 1, valueDescription: String? = null) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = maxLines,
            modifier = if (valueDescription != null) Modifier.clearAndSetSemantics { contentDescription = valueDescription } else Modifier,
        )
    }
}

/** The space between the screen's parts: title, numbers, map, height chart and Share button. */
private val SECTION_SPACING = 12.dp

/**
 * The map never gets less than this for the height chart's sake: room for the route between the
 * scale bar and legend at the top and the "Show whole route" button at the bottom.
 */
private val MIN_MAP_HEIGHT = 200.dp

private fun tripDate(startEpochSecond: Long, startZoneId: String) =
    Instant.ofEpochSecond(startEpochSecond).atZone(ZoneId.of(startZoneId)).toLocalDate()

private fun tripDurationSeconds(trip: TripSummary): Long {
    val end = trip.endEpochSecond ?: trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
    return (end - trip.startEpochSecond).coerceAtLeast(0L)
}
