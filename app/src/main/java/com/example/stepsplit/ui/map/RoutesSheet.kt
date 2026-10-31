package com.example.stepsplit.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.data.route.ImportedRoute
import com.example.stepsplit.domain.route.RouteText
import com.example.stepsplit.ui.trips.formatDistance
import java.text.NumberFormat
import kotlin.math.roundToInt

/** What the Routes list can do with a route; see [OpenMapViewModel] for each. */
class ImportedRouteActions(
    val onShowChange: (ImportedRoute, Boolean) -> Unit,
    val onZoomTo: (ImportedRoute) -> Unit,
    val onRename: (ImportedRoute, String) -> Unit,
    val onDelete: (ImportedRoute) -> Unit,
)

/**
 * The Map tab's Routes list, over the map: every imported route, newest first, each with a box to
 * show or hide it, its distance, climb and size, and a menu to zoom to it, rename it or delete it.
 * Tapping a route shows the whole of it on the map. Import opens the system file picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutesSheet(routes: List<ImportedRoute>, onImport: () -> Unit, actions: ImportedRouteActions, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf<ImportedRoute?>(null) }
    var deleting by remember { mutableStateOf<ImportedRoute?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.routes_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = onImport) {
                    Icon(painterResource(R.drawable.ic_route_24), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.routes_import_action))
                }
            }
            if (routes.isEmpty()) {
                Text(
                    text = stringResource(R.string.routes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                // A long list scrolls inside the sheet, with the note under it still showing.
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).padding(top = 4.dp)) {
                    items(routes, key = { it.id }) { route ->
                        HorizontalDivider()
                        RouteRow(
                            route = route,
                            onShowChange = { actions.onShowChange(route, it) },
                            onZoomTo = { actions.onZoomTo(route) },
                            onRename = { renaming = route },
                            onDelete = { deleting = route },
                        )
                    }
                }
                HorizontalDivider()
            }
            Text(
                text = stringResource(R.string.routes_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
            )
        }
    }

    renaming?.let { route ->
        RenameRouteDialog(
            initialName = route.name.orEmpty(),
            onSave = { name ->
                actions.onRename(route, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { route ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.routes_delete_title)) },
            text = { Text(stringResource(R.string.routes_delete_message, routeName(route))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onDelete(route)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.routes_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun RouteRow(
    route: ImportedRoute,
    onShowChange: (Boolean) -> Unit,
    onZoomTo: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = routeName(route)
    var menuOpen by remember { mutableStateOf(false) }
    val showLabel = stringResource(R.string.routes_show_route, name)
    val moreLabel = stringResource(R.string.routes_more, name)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = route.shown, onCheckedChange = onShowChange, modifier = Modifier.semantics { contentDescription = showLabel })
        Column(
            modifier = Modifier.weight(1f).clickable(onClick = onZoomTo).padding(vertical = 8.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(routeSummary(route), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = moreLabel)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routes_zoom_action)) },
                    onClick = {
                        menuOpen = false
                        onZoomTo()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routes_rename_action)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routes_delete_action), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** The route's name, or "Imported route" when neither it nor its file has one. */
@Composable
internal fun routeName(route: ImportedRoute): String = route.name ?: stringResource(R.string.routes_unnamed)

/** "12.40 km · ↑420 m · 2 lines · 1,234 points · 5 waypoints": no climb without heights, no line count for one line, no waypoints for none. */
@Composable
private fun routeSummary(route: ImportedRoute): String {
    val parts = buildList {
        add(formatDistance(route.distanceMeters))
        route.climbMeters?.let { add(stringResource(R.string.routes_climb_format, it.roundToInt())) }
        if (route.lineCount > 1) add(pluralStringResource(R.plurals.routes_lines, route.lineCount, route.lineCount))
        add(pluralStringResource(R.plurals.routes_points, route.pointCount, NumberFormat.getIntegerInstance().format(route.pointCount)))
        if (route.waypointCount > 0) add(pluralStringResource(R.plurals.routes_waypoints, route.waypointCount, route.waypointCount))
    }
    return parts.joinToString(stringResource(R.string.routes_separator))
}

/** Renames a route: up to [RouteText.MAX_NAME_LENGTH] characters. Empty goes back to the file's name. */
@Composable
private fun RenameRouteDialog(initialName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routes_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(RouteText.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.routes_rename_label)) },
                supportingText = {
                    Text(text = "${name.length}/${RouteText.MAX_NAME_LENGTH}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.routes_rename_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
