package com.example.stepsplit.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.R
import com.example.stepsplit.data.mappack.DownloadProgress
import com.example.stepsplit.data.mappack.InstalledMapPack
import com.example.stepsplit.data.mappack.MapPackRegion
import com.example.stepsplit.data.mappack.MapPacksState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Offline maps card can do; see [OfflineMapsViewModel]. */
class OfflineMapsActions(
    val onRetry: () -> Unit,
    val onDownload: (regionId: String) -> Unit,
    val onCancelDownload: () -> Unit,
    val onDelete: (regionId: String) -> Unit,
    val onSetWifiOnly: (Boolean) -> Unit,
)

/**
 * Settings' Offline maps card: each map on the server (or on the phone) with its size, Download,
 * the download's progress with Cancel, then Update and Delete; and the Wi-Fi only switch.
 */
@Composable
internal fun OfflineMapsSection(state: MapPacksState, actions: OfflineMapsActions) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<InstalledMapPack?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.offline_maps_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.offline_maps_intro), style = MaterialTheme.typography.bodyMedium)

            val regionIds = (state.regions.map { it.id } + state.installed.map { it.regionId }).distinct()
            for (regionId in regionIds) {
                RegionRow(
                    name = regionName(context, regionId),
                    region = state.regions.firstOrNull { it.id == regionId },
                    installed = state.installed(regionId),
                    download = state.download?.takeIf { it.regionId == regionId },
                    otherDownloadRunning = state.download != null && state.download.regionId != regionId,
                    actions = actions,
                    onDelete = { confirmDelete = it },
                )
            }
            if (state.regions.isEmpty()) ManifestStatusRow(state.manifest, actions.onRetry)

            state.problem?.let { problem ->
                Text(
                    stringResource(
                        when (problem) {
                            MapPacksState.Problem.DOWNLOAD_FAILED -> R.string.offline_maps_problem_download_failed
                            MapPacksState.Problem.CHECK_FAILED -> R.string.offline_maps_problem_check_failed
                            MapPacksState.Problem.NOT_ENOUGH_SPACE -> R.string.offline_maps_problem_space
                            MapPacksState.Problem.NO_STORAGE -> R.string.offline_maps_problem_storage
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // For the next download: a running one keeps its own choice (Cancel, switch, Download again to change it).
            if (state.download == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = state.wifiOnly,
                        role = Role.Switch,
                        onValueChange = actions.onSetWifiOnly,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.offline_maps_wifi_only),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    // The whole row toggles (and is what a screen reader announces), so the switch itself takes no clicks.
                    Switch(checked = state.wifiOnly, onCheckedChange = null)
                }
            }
            Text(stringResource(R.string.offline_maps_note), style = MaterialTheme.typography.bodySmall)
        }
    }

    confirmDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.offline_maps_delete_confirm_title, regionName(context, pack.regionId))) },
            text = { Text(stringResource(R.string.offline_maps_delete_confirm_message, formatSize(context, pack.bytes))) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(pack.regionId)
                    confirmDelete = null
                }) { Text(stringResource(R.string.offline_maps_delete_action)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.offline_maps_keep_action)) } },
        )
    }
}

@Composable
private fun RegionRow(
    name: String,
    region: MapPackRegion?,
    installed: InstalledMapPack?,
    download: DownloadProgress?,
    otherDownloadRunning: Boolean,
    actions: OfflineMapsActions,
    onDelete: (InstalledMapPack) -> Unit,
) {
    val context = LocalContext.current
    val needsNewerApp = region != null && region.minAppVersionCode > BuildConfig.VERSION_CODE
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            download != null -> {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (download.checking || download.totalBytes <= 0) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            download.checking -> stringResource(R.string.offline_maps_checking)
                            download.waiting == DownloadProgress.Waiting.WIFI -> stringResource(R.string.offline_maps_waiting_wifi)
                            download.waiting == DownloadProgress.Waiting.NETWORK -> stringResource(R.string.offline_maps_waiting_network)
                            else -> stringResource(
                                R.string.offline_maps_progress,
                                formatSize(context, download.downloadedBytes),
                                formatSize(context, download.totalBytes),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (!download.checking) {
                        OutlinedButton(onClick = actions.onCancelDownload) { Text(stringResource(R.string.offline_maps_cancel_action)) }
                    }
                }
            }
            installed != null -> {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.offline_maps_installed, formatVersion(installed.version), formatSize(context, installed.bytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val update = region?.takeIf { offersUpdate(it.version, installed.version) && !needsNewerApp }
                if (update != null) {
                    Text(stringResource(R.string.offline_maps_update_available, formatSize(context, update.totalBytes)), style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (update != null) {
                        Button(onClick = { actions.onDownload(update.id) }, enabled = !otherDownloadRunning) {
                            Text(stringResource(R.string.offline_maps_update_action))
                        }
                    }
                    OutlinedButton(onClick = { onDelete(installed) }) { Text(stringResource(R.string.offline_maps_delete_action)) }
                }
            }
            region != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.offline_maps_region_with_size, name, formatSize(context, region.totalBytes)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    Button(onClick = { actions.onDownload(region.id) }, enabled = !otherDownloadRunning && !needsNewerApp) {
                        Text(stringResource(R.string.offline_maps_download_action))
                    }
                }
            }
        }
        if (needsNewerApp) Text(stringResource(R.string.offline_maps_needs_newer_app), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ManifestStatusRow(status: MapPacksState.ManifestStatus, onRetry: () -> Unit) {
    when (status) {
        MapPacksState.ManifestStatus.NOT_CHECKED, MapPacksState.ManifestStatus.CHECKING -> {
            Text(stringResource(R.string.offline_maps_looking), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        MapPacksState.ManifestStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offline_maps_server_failed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.offline_maps_retry_action)) }
        }
        MapPacksState.ManifestStatus.NEEDS_NEWER_APP ->
            Text(stringResource(R.string.offline_maps_needs_newer_app), style = MaterialTheme.typography.bodyMedium)
        MapPacksState.ManifestStatus.CHECKED -> Unit
    }
}

/** "Israel" in the app's language; a region the app doesn't know yet by its id. */
internal fun regionName(context: Context, regionId: String): String = when (regionId) {
    "israel" -> context.getString(R.string.offline_maps_region_israel)
    else -> regionId.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/**
 * Whether the server's pack replaces the installed one: whenever its version differs. The server is
 * the one source of truth, so version names need no order (`2026-10-30-2` after `2026-10-30c`), and
 * a broken pack is withdrawn by publishing the older version again.
 */
internal fun offersUpdate(serverVersion: String, installedVersion: String): Boolean = serverVersion != installedVersion

/** A pack's version is its build date: shown as its month, "October 2026". */
internal fun formatVersion(version: String): String = runCatching {
    LocalDate.parse(version).format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
}.getOrDefault(version)
