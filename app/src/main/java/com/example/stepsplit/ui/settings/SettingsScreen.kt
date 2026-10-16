package com.example.stepsplit.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.R
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceHealthSnapshot
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.ui.common.ScreenHeader
import com.example.stepsplit.ui.common.formatCount
import com.example.stepsplit.ui.common.statusText
import com.example.stepsplit.ui.common.text
import java.time.Instant

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onSetDailyGoal: (Long) -> Unit,
    onSetThresholds: (ClassificationThresholds) -> Unit,
    onResetThresholds: () -> Unit,
    onSetKeepDeletedTripsInStats: (Boolean) -> Unit,
    onGenerateSampleData: () -> Unit,
    onRunStepSourceCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember { listOf(SettingsSection.Goals, SettingsSection.Advanced, SettingsSection.Trips, SettingsSection.Status, SettingsSection.Debug) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ScreenHeader(title = stringResource(R.string.settings_title)) }
        items(sections) { section ->
            when (section) {
                SettingsSection.Goals -> GoalsSection(uiState, onSetDailyGoal)
                SettingsSection.Advanced -> AdvancedThresholdsSection(uiState.thresholds, onSetThresholds, onResetThresholds)
                SettingsSection.Trips -> TripsSection(uiState.keepDeletedTripsInStats, onSetKeepDeletedTripsInStats)
                SettingsSection.Status -> StatusSection(uiState)
                SettingsSection.Debug -> if (BuildConfig.DEBUG) DebugSection(uiState, onGenerateSampleData, onRunStepSourceCheck)
            }
        }
    }
}

private enum class SettingsSection { Goals, Advanced, Trips, Status, Debug }

@Composable
private fun GoalsSection(uiState: SettingsUiState, onSetDailyGoal: (Long) -> Unit) {
    var goalText by remember(uiState.goals.dailyGoalSteps) { mutableStateOf(uiState.goals.dailyGoalSteps.toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = goalText,
                onValueChange = { goalText = it },
                label = { Text(stringResource(R.string.settings_daily_goal_label)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uiState.goalInputError,
                supportingText = {
                    if (uiState.goalInputError) Text(stringResource(R.string.settings_goal_invalid_error))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.settings_weekly_goal_label, formatCount(uiState.goals.weeklyGoalSteps)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { goalText.toLongOrNull()?.let(onSetDailyGoal) ?: onSetDailyGoal(-1) }) {
                Text(stringResource(R.string.settings_save))
            }
        }
    }
}

@Composable
private fun AdvancedThresholdsSection(
    thresholds: ClassificationThresholds,
    onSetThresholds: (ClassificationThresholds) -> Unit,
    onResetThresholds: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember(thresholds) { mutableStateOf(thresholds) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A Show / Hide button rather than a switch: this only reveals the fields below, and a
            // switch read as turning automatic classification itself on or off (it is always on).
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_advanced_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.settings_advanced_hide else R.string.settings_advanced_show))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }
            }

            if (expanded) {
                ThresholdField(stringResource(R.string.settings_threshold_max_gap), current.maxGapMinutes) {
                    current = current.copy(maxGapMinutes = it)
                }
                ThresholdField(stringResource(R.string.settings_threshold_idle_finalize), current.idleFinalizeMinutes) {
                    current = current.copy(idleFinalizeMinutes = it)
                }
                ThresholdField(stringResource(R.string.settings_threshold_min_duration), current.minBoutDurationMinutes) {
                    current = current.copy(minBoutDurationMinutes = it)
                }
                ThresholdField(stringResource(R.string.settings_threshold_min_active), current.minActiveMinutes) {
                    current = current.copy(minActiveMinutes = it)
                }
                ThresholdField(
                    stringResource(R.string.settings_threshold_min_steps),
                    current.minSteps.toInt(),
                ) { current = current.copy(minSteps = it.toLong()) }
                ThresholdField(
                    stringResource(R.string.settings_threshold_min_cadence),
                    current.minCadenceStepsPerMinute.toInt(),
                ) { current = current.copy(minCadenceStepsPerMinute = it.toDouble()) }

                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSetThresholds(current) }) { Text(stringResource(R.string.settings_save)) }
                    OutlinedButton(onClick = { current = ClassificationThresholds.DEFAULT; onResetThresholds() }) {
                        Text(stringResource(R.string.settings_reset_thresholds))
                    }
                }
            }

            Text(text = stringResource(R.string.settings_classification_limitation_title), style = MaterialTheme.typography.titleSmall)
            Text(text = stringResource(R.string.settings_classification_limitation_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** GPS trip settings. A switch here, unlike the thresholds card's Show / Hide: it really turns something on and off. */
@Composable
private fun TripsSection(keepDeletedTripsInStats: Boolean, onSetKeepDeletedTripsInStats: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_trips_section_title), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = keepDeletedTripsInStats,
                    role = Role.Switch,
                    onValueChange = onSetKeepDeletedTripsInStats,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_keep_deleted_trips_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                // The whole row toggles (and is what a screen reader announces), so the switch itself takes no clicks.
                Switch(checked = keepDeletedTripsInStats, onCheckedChange = null)
            }
            Text(stringResource(R.string.settings_keep_deleted_trips_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ThresholdField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            text = newValue
            newValue.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * "Permission status" reflects source availability only (permission granted, Health Connect
 * present) - see [StepSourceAvailability]. "Data collection status" reflects whether syncs are
 * actually succeeding - see [com.example.stepsplit.domain.model.SyncFailure] - which is not the
 * same thing: availability being fine does not mean the last sync actually succeeded, so this
 * must never just echo the availability text back a second time under a different heading.
 */
@Composable
private fun StatusSection(uiState: SettingsUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_permission_status_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = uiState.availability?.statusText() ?: stringResource(R.string.status_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_data_collection_status_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = collectionStatusText(uiState),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * A recorded sync failure always wins - it means collection is *not* actually working right now,
 * regardless of what availability says. Only once there is no such failure does this describe
 * [StepCollectionHealth] instead: never a claim that collection is active *right now* - see that
 * enum's own doc comment for why the app genuinely cannot know that, only whether a sample has ever
 * been observed at all.
 */
@Composable
private fun collectionStatusText(uiState: SettingsUiState): String {
    val failure = uiState.syncFailure
    if (failure != null) return failure.category.text()
    return when (uiState.availability) {
        StepSourceAvailability.Available -> when (uiState.collectionHealth) {
            // A successful sync/read is not by itself proof of working collection - see
            // StepCollectionHealth's own doc comment. Must never claim "active"; only ever
            // "waiting" (nothing observed yet), "stale" (the newest sample is too old to call
            // healthy - see StepCollectionHealth.STALE's own doc comment), or "has been observed"
            // (historical evidence, not a claim about this exact moment).
            StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE -> stringResource(R.string.status_waiting_for_first_sample)
            StepCollectionHealth.STALE -> stringResource(R.string.status_stale_data)
            else -> stringResource(R.string.status_sample_observed)
        }

        null -> stringResource(R.string.status_unknown)
        else -> uiState.availability.statusText()
    }
}

@Composable
private fun DebugSection(
    uiState: SettingsUiState,
    onGenerateSampleData: () -> Unit,
    onRunStepSourceCheck: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_debug_section_title), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onGenerateSampleData) {
                Text(stringResource(R.string.settings_debug_seed_action))
            }

            DebugDiagnosticsSection(uiState, onRunStepSourceCheck)
        }
    }
}

/**
 * Debug-only production acquisition diagnostics - never shown outside debug builds (guarded by the
 * caller, [DebugSection]). Reads directly from [SettingsUiState.healthSnapshot]
 * ([com.example.stepsplit.data.stepsource.StepSourceHealthStore]'s persisted state), which is
 * populated exclusively by the real subscribe/read code path in
 * [com.example.stepsplit.data.stepsource.HealthConnectStepSource] - "Run step source check" below
 * re-runs that exact same path (via [com.example.stepsplit.data.repository.StepRepository.syncNow])
 * rather than a separate diagnostic-only acquisition attempt, so this can never show a healthier
 * picture than what production sync itself actually does.
 */
@Composable
private fun DebugDiagnosticsSection(uiState: SettingsUiState, onRunStepSourceCheck: () -> Unit) {
    val health = uiState.healthSnapshot
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_debug_diagnostics_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        DebugDiagnosticRow(R.string.settings_debug_label_package, "${BuildConfig.APPLICATION_ID} (${BuildConfig.BUILD_TYPE})")
        DebugDiagnosticRow(R.string.settings_debug_label_permission, uiState.availability?.statusText() ?: stringResource(R.string.status_unknown))
        DebugDiagnosticRow(R.string.settings_debug_label_subscription, debugSubscriptionText(health))
        DebugDiagnosticRow(R.string.settings_debug_label_read_attempt, debugTimestampText(health.latestReadAttemptEpochSecond))
        DebugDiagnosticRow(R.string.settings_debug_label_requested_window, debugWindowText(health))
        DebugDiagnosticRow(R.string.settings_debug_label_read_success, debugTimestampText(health.latestSuccessfulReadEpochSecond))
        DebugDiagnosticRow(R.string.settings_debug_label_read_failure, debugReadFailureText(health))
        DebugDiagnosticRow(R.string.settings_debug_label_ever_observed, health.everObservedSample.toString())
        DebugDiagnosticRow(R.string.settings_debug_label_latest_sample, debugTimestampText(health.latestSampleEpochSecond))
        DebugDiagnosticRow(R.string.settings_debug_label_interval_count, health.latestReadIntervalCount?.toString() ?: "-")
        DebugDiagnosticRow(R.string.settings_debug_label_consecutive_empty, health.consecutiveEmptyReads.toString())
        DebugDiagnosticRow(R.string.settings_debug_label_stored_buckets, uiState.storedBucketCount?.toString() ?: "-")
        uiState.debugStepSourceCheckResult?.let { DebugDiagnosticRow(R.string.settings_debug_label_check_result, it) }
        OutlinedButton(onClick = onRunStepSourceCheck, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.settings_debug_run_check_action))
        }

        uiState.deviceDiagnostics?.let { device ->
            Text(
                stringResource(R.string.settings_debug_environment_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            DebugDiagnosticRow(R.string.settings_debug_label_device, "${device.manufacturer} ${device.model}")
            DebugDiagnosticRow(R.string.settings_debug_label_android_version, "${device.androidRelease} (API ${device.androidSdkInt})")
            DebugDiagnosticRow(R.string.settings_debug_label_health_connect_status, device.healthConnectAvailable.toString())
            DebugDiagnosticRow(R.string.settings_debug_label_step_counting_supported, device.onDeviceStepCountingSupported.toString())
        }
    }
}

/** "-" when never attempted; otherwise the latest subscription-check outcome. */
@Composable
private fun debugSubscriptionText(health: StepSourceHealthSnapshot): String {
    val at = debugTimestampText(health.latestSubscriptionAtEpochSecond)
    return when (health.latestSubscriptionSucceeded) {
        true -> "OK @ $at"
        false -> "FAILED (${health.latestSubscriptionFailureCategory}, code=${health.latestSubscriptionFailureStatusCode}) @ $at"
        null -> "-"
    }
}

/** "-" when no read has ever been attempted; otherwise the `[start, end)` window the most recent attempt actually requested - debug-only, see [StepSourceHealthSnapshot.latestRequestedWindowStartEpochSecond]. */
@Composable
private fun debugWindowText(health: StepSourceHealthSnapshot): String {
    val start = health.latestRequestedWindowStartEpochSecond
    val end = health.latestRequestedWindowEndEpochSecond
    if (start == null || end == null) return "-"
    return "[${debugTimestampText(start)}, ${debugTimestampText(end)})"
}

private fun debugTimestampText(epochSecond: Long?): String = epochSecond?.let { Instant.ofEpochSecond(it).toString() } ?: "-"

/**
 * "-" when no read has ever failed; otherwise the *last read failure ever recorded*, regardless of
 * whether a later read has since succeeded (see [StepSourceHealthStore.recordReadFailure]'s own
 * "last failure ever" doc comment) - a real-device investigation needs to see this even after
 * things start working again.
 */
@Composable
private fun debugReadFailureText(health: StepSourceHealthSnapshot): String {
    val category = health.latestReadFailureCategory ?: return "-"
    val at = debugTimestampText(health.latestReadFailureAtEpochSecond)
    return "$category (code=${health.latestReadFailureStatusCode}) @ $at"
}

@Composable
private fun DebugDiagnosticRow(@StringRes labelRes: Int, value: String) {
    Text(
        text = "${stringResource(labelRes)}: $value",
        style = MaterialTheme.typography.bodySmall,
    )
}
