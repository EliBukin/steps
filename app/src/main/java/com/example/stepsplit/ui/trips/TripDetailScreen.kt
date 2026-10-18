package com.example.stepsplit.ui.trips

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
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
import com.example.stepsplit.domain.model.TripSpot
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
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TripDetailScreen(
    uiState: TripDetailUiState,
    onSaveTripDetails: (name: String, type: TripType) -> Unit,
    onDeleteTrip: () -> Unit,
    onRenameSpot: (spotId: Long, name: String) -> Unit,
    onDeleteSpot: (spotId: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEdit by rememberSaveable { mutableStateOf(false) }
    // The spot whose marker was tapped, and which of its dialogs is open.
    var selectedSpotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renamingSpot by rememberSaveable { mutableStateOf(false) }
    var confirmingSpotDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
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
    //
    // The spots go in as waypoints, named as the app shows them. Their heights follow the track
    // points' rule; should only one of the two conversions have worked, the spots go without a
    // height rather than mix two references in one file.
    val shareGpx: () -> Unit = {
        val points = uiState.points
        val sameHeightReference = uiState.spotHeightsAboveSeaLevel == uiState.heightsAboveSeaLevel
        val waypoints = uiState.spots.map { spot ->
            GpxExport.Waypoint(
                latitude = spot.latitude,
                longitude = spot.longitude,
                altitudeMeters = spot.altitudeMeters?.takeIf { sameHeightReference },
                epochSecond = spot.capturedAtEpochSecond,
                name = spotDisplayName(resources, spot),
            )
        }
        val tripId = trip.id
        val tripName = trip.name
        val fileName = GpxExport.suggestedFileName(date)
        coroutineScope.launch {
            val file = withContext(Dispatchers.Default) {
                runCatching {
                    val gpx = GpxExport.toGpx(points, name = tripName, waypoints = waypoints)
                    withContext(Dispatchers.IO) { GpxShare.writeFile(context.cacheDir, tripId, fileName, gpx) }
                }.getOrNull()
            }
            val opened = file != null && runCatching {
                context.startActivity(GpxShare.chooserIntent(context, file, gpxShareSubject, gpxShareChooserTitle))
            }.isSuccess
            if (!opened) Toast.makeText(context, gpxShareFailureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    // The map takes the whole screen under the title bar, and the trip's numbers sit in a panel over
    // its bottom that opens upward. Nothing scrolls the map: the map is a native view, and inside a
    // vertically scrollable container any drag with a vertical component (an upward pan, a diagonal
    // drag, a pinch) is claimed by the container after a few pixels and cancelled for the map, so the
    // map stalls mid-gesture. Only the open panel's own content scrolls, on a short phone.
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = detailsExpanded) { detailsExpanded = false }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
            }
            TripTypeIcon(uiState.type, size = 24.dp, modifier = Modifier.padding(end = 8.dp))
            // An unnamed trip is titled by its date, as it always was; a named one shows the date under its name.
            Column(modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                // One line, so the bar stays short and the map keeps its room; the full name is in Edit trip.
                Text(
                    text = trip.name ?: dateTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trip.name != null) {
                    Text(text = dateTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Share opens the system share sheet, whose first entry is the app's own "Save file".
            IconButton(onClick = shareGpx, enabled = uiState.points.isNotEmpty()) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.trip_detail_share_gpx_action))
            }
            IconButton(onClick = { showEdit = true }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.trip_detail_edit_action))
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.trip_detail_delete_action))
            }
        }

        MapUnderPanel(
            modifier = Modifier.fillMaxWidth().weight(1f),
            panel = {
                TripDetailsPanel(
                    trip = trip,
                    motion = uiState.motion,
                    elevation = uiState.elevation,
                    heightsAboveSeaLevel = uiState.heightsAboveSeaLevel,
                    expanded = detailsExpanded,
                    onExpandedChange = { detailsExpanded = it },
                )
            },
            map = { bottomInset ->
                if (uiState.points.isEmpty()) {
                    Text(
                        text = stringResource(R.string.trip_detail_route_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    TripRouteMapCard(
                        points = uiState.points,
                        speedColoring = uiState.speedColoring,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        spots = uiState.spots,
                        onSpotClick = { spotId ->
                            selectedSpotId = spotId
                            renamingSpot = false
                            confirmingSpotDelete = false
                        },
                        bottomInset = bottomInset,
                    )
                }
            },
        )
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

    val selectedSpot = uiState.spots.firstOrNull { it.id == selectedSpotId }
    if (selectedSpot != null) {
        val closeSpot = {
            selectedSpotId = null
            renamingSpot = false
            confirmingSpotDelete = false
        }
        when {
            renamingSpot -> RenameSpotDialog(
                initialName = selectedSpot.name.orEmpty(),
                defaultName = stringResource(R.string.trip_spot_default_name, selectedSpot.number),
                onSave = { name ->
                    onRenameSpot(selectedSpot.id, name)
                    renamingSpot = false
                },
                onDismiss = { renamingSpot = false },
            )
            confirmingSpotDelete -> AlertDialog(
                onDismissRequest = { confirmingSpotDelete = false },
                title = { Text(stringResource(R.string.trip_spot_delete_confirm_title, spotDisplayName(resources, selectedSpot))) },
                text = { Text(stringResource(R.string.trip_spot_delete_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteSpot(selectedSpot.id)
                        closeSpot()
                    }) { Text(stringResource(R.string.trip_spot_delete_action)) }
                },
                dismissButton = { TextButton(onClick = { confirmingSpotDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
            else -> SpotDialog(
                spot = selectedSpot,
                zoneId = ZoneId.of(trip.startZoneId),
                heightAboveSeaLevel = uiState.spotHeightsAboveSeaLevel,
                onRename = { renamingSpot = true },
                onDelete = { confirmingSpotDelete = true },
                onDismiss = closeSpot,
            )
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

/**
 * Lays [map] out over the whole area and [panel] over its bottom, at the panel's own height, but never
 * taller than [MAX_PANEL_FRACTION] of the area. [map] gets the panel's height as its bottom inset, so
 * it can keep its buttons and the framed route above the panel. The panel is measured first, so even
 * the first frame frames the route in the right place.
 */
@Composable
private fun MapUnderPanel(modifier: Modifier, panel: @Composable () -> Unit, map: @Composable (bottomInset: Dp) -> Unit) {
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val panelPlaceable = subcompose(MapPanelSlot.Panel, panel).first()
            .measure(Constraints(minWidth = width, maxWidth = width, maxHeight = (height * MAX_PANEL_FRACTION).toInt()))
        val bottomInset = panelPlaceable.height.toDp()
        val mapPlaceable = subcompose(MapPanelSlot.Map) { map(bottomInset) }.first().measure(Constraints.fixed(width, height))
        layout(width, height) {
            mapPlaceable.place(0, 0)
            panelPlaceable.place(0, height - panelPlaceable.height)
        }
    }
}

private enum class MapPanelSlot { Map, Panel }

/**
 * The trip's numbers in a panel over the bottom of the map. Closed, it shows the distance and duration
 * and "Trip details"; open, it adds the distance and duration large, then the time, moving time (see
 * [TripMotion]), climb and descent, and top speed, then the height chart when enough points have a
 * height (see [TripElevation]). A tap on its top row, or a drag up or down on it, opens or closes it;
 * Back closes it. [motion] is null when the route has fewer than two points and [elevation] when too
 * few points have a height: their numbers then show as a dash.
 */
@Composable
private fun TripDetailsPanel(
    trip: TripSummary,
    motion: TripMotion.Motion?,
    elevation: TripElevation.Elevation?,
    heightsAboveSeaLevel: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val distance = formatDistance(trip.distanceMeters)
    val duration = formatElapsed(tripDurationSeconds(trip))
    val dragThresholdPx = with(LocalDensity.current) { PANEL_DRAG_THRESHOLD.toPx() }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val toggleLabel = stringResource(if (expanded) R.string.cd_trip_details_hide else R.string.cd_trip_details_show)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = PANEL_CORNER, topEnd = PANEL_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> dragTotal += delta },
                        onDragStarted = { dragTotal = 0f },
                        onDragStopped = { onExpandedChange(panelExpandedAfterDrag(expanded, dragTotal, dragThresholdPx)) },
                    )
                    .clickable(onClickLabel = toggleLabel, role = Role.Button) { onExpandedChange(!expanded) }
                    .padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (expanded) stringResource(R.string.trip_detail_sheet_title) else stringResource(R.string.trip_detail_peek_format, distance, duration),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!expanded) {
                        Text(
                            text = stringResource(R.string.trip_detail_sheet_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LargeNumbersRow(
                        stringResource(R.string.trip_detail_distance_label) to distance,
                        stringResource(R.string.trip_detail_duration_label) to duration,
                    )
                    TripNumbersCard(trip, motion, elevation)
                    if (elevation != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.trip_detail_elevation_label),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TripHeightChartCard(elevation = elevation, aboveSeaLevel = heightsAboveSeaLevel)
                        }
                    }
                }
            }
        }
    }
}

/** Two big numbers side by side, label over value, with a thin line between them. */
@Composable
private fun LargeNumbersRow(start: Pair<String, String>, end: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        LargeNumber(label = start.first, value = start.second, modifier = Modifier.weight(1f))
        VerticalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        LargeNumber(label = end.first, value = end.second, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LargeNumber(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The rest of the trip's numbers in a card of two rows and two columns, with thin lines between
 * them: when it ran and the moving time, then the climb and descent and the top speed. Mirrors in
 * right-to-left languages.
 */
@Composable
private fun TripNumbersCard(trip: TripSummary, motion: TripMotion.Motion?, elevation: TripElevation.Elevation?) {
    val zoneId = ZoneId.of(trip.startZoneId)
    val locale = LocalConfiguration.current.locales[0]
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SummaryCell(
                    label = stringResource(R.string.trip_detail_time_label),
                    value = stringResource(
                        R.string.trip_detail_time_range_format,
                        formatClockTimeInZone(trip.startEpochSecond, zoneId),
                        trip.endEpochSecond?.let { formatClockTimeInZone(it, zoneId) } ?: "-",
                    ),
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                SummaryCell(
                    label = stringResource(R.string.trip_detail_moving_time_label),
                    value = motion?.let { formatElapsed(it.movingSeconds) } ?: NO_VALUE,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
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
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    SummaryCell(stringResource(R.string.trip_detail_climb_descent_label), NO_VALUE, modifier = Modifier.weight(1f))
                }
                VerticalDivider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                SummaryCell(
                    label = stringResource(R.string.trip_detail_top_speed_label),
                    // Formatted like the speed legend's ends, so the two always agree on the map's scale.
                    value = motion?.topSpeedMetersPerSecond?.let {
                        stringResource(R.string.trip_map_speed_format, formatLegendSpeed(it * METERS_PER_SECOND_TO_KMH, locale))
                    } ?: NO_VALUE,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Where a drag on the details panel leaves it: a drag up of at least [thresholdPx] opens it, a drag
 * down of at least that closes it, and anything shorter leaves it as it was. [dragPx] is the total
 * movement, negative upward as on screen.
 */
internal fun panelExpandedAfterDrag(expanded: Boolean, dragPx: Float, thresholdPx: Float): Boolean = when {
    dragPx <= -thresholdPx -> true
    dragPx >= thresholdPx -> false
    else -> expanded
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
 * What a tapped spot marker opens: the spot's number badge (as on the map), its name or "Spot N",
 * the time it was marked, and its height when it is above sea level (Android 14 and newer - see
 * [com.example.stepsplit.data.trip.SeaLevelHeights]; a height above the ellipsoid would be about
 * 20 m off in Israel, so none is shown then). Rename and Delete; Close or a tap outside closes it.
 */
@Composable
private fun SpotDialog(
    spot: TripSpot,
    zoneId: ZoneId,
    heightAboveSeaLevel: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SpotBadge(spot.number) },
        title = { Text(text = spotDisplayName(LocalResources.current, spot), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (spot.name != null) {
                    Text(
                        text = stringResource(R.string.trip_spot_default_name, spot.number),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.trip_spot_time_format, formatClockTimeInZone(spot.capturedAtEpochSecond, zoneId)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val height = spot.altitudeMeters
                if (heightAboveSeaLevel && height != null) {
                    // "410 m below sea level" reads better than "-410 m above".
                    val meters = formatHeightNumber(abs(height), locale)
                    Text(
                        text = stringResource(
                            if (height.roundToLong() < 0) R.string.trip_spot_height_below_format else R.string.trip_spot_height_above_format,
                            meters,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trip_spot_close_action)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.trip_spot_delete_action)) }
                TextButton(onClick = onRename) { Text(stringResource(R.string.trip_spot_rename_action)) }
            }
        },
    )
}

/** A spot's number in a white disc with a dark green ring - the map's marker, at dialog size. */
@Composable
private fun SpotBadge(number: Int) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.White, CircleShape)
            .border(2.5.dp, SpotBadgeColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = number.toString(), color = SpotBadgeColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** The map markers' ring and number color (see `TripRouteMap`). */
private val SpotBadgeColor = Color(0xFF1B6D5A)

/**
 * Renames a spot: up to [TripName.MAX_LENGTH] characters, like a trip's name. Empty goes back to
 * [defaultName] ("Spot 3"), which the field shows as its placeholder.
 */
@Composable
private fun RenameSpotDialog(initialName: String, defaultName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trip_spot_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = TripName.limit(it) },
                label = { Text(stringResource(R.string.trip_edit_name_label)) },
                placeholder = { Text(defaultName) },
                supportingText = {
                    Text(text = "${name.length}/${TripName.MAX_LENGTH}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.trip_edit_save_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/**
 * One label-over-value cell of the numbers card: time, moving time, climb and descent, or top speed.
 * [valueDescription], when given, is what TalkBack reads instead of [value] (for "↑230 ↓222 m").
 */
@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier, maxLines: Int = 1, valueDescription: String? = null) {
    Column(modifier = modifier.padding(vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            modifier = if (valueDescription != null) Modifier.clearAndSetSemantics { contentDescription = valueDescription } else Modifier,
        )
    }
}

/** A number the trip doesn't have (too few points, or none with a height). */
private const val NO_VALUE = "–"

/** The details panel never covers more than this share of the map. */
private const val MAX_PANEL_FRACTION = 0.7f

/** The details panel's rounded top corners. */
private val PANEL_CORNER = 24.dp

/** How far a drag on the details panel must go to open or close it. */
private val PANEL_DRAG_THRESHOLD = 24.dp

private fun tripDate(startEpochSecond: Long, startZoneId: String) =
    Instant.ofEpochSecond(startEpochSecond).atZone(ZoneId.of(startZoneId)).toLocalDate()

private fun tripDurationSeconds(trip: TripSummary): Long {
    val end = trip.endEpochSecond ?: trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
    return (end - trip.startEpochSecond).coerceAtLeast(0L)
}
