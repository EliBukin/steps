package com.example.stepsplit.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.R
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.data.stepsource.StepSourceHealthSnapshot
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepCollectionHealth
import com.example.stepsplit.ui.common.statusText
import com.example.stepsplit.ui.common.text
import java.time.Instant

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onSetDailyGoal: (Long) -> Unit,
    onSetThresholds: (ClassificationThresholds) -> Unit,
    onResetThresholds: () -> Unit,
    onGenerateSampleData: () -> Unit,
    onRunStepSourceCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember { listOf(SettingsSection.Goals, SettingsSection.Advanced, SettingsSection.Status, SettingsSection.Debug) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge) }
        items(sections) { section ->
            when (section) {
                SettingsSection.Goals -> GoalsSection(uiState, onSetDailyGoal)
                SettingsSection.Advanced -> AdvancedThresholdsSection(uiState.thresholds, onSetThresholds, onResetThresholds)
                SettingsSection.Status -> StatusSection(uiState)
                SettingsSection.Debug -> if (BuildConfig.DEBUG) DebugSection(uiState, onGenerateSampleData, onRunStepSourceCheck)
            }
        }
    }
}

private enum class SettingsSection { Goals, Advanced, Status, Debug }

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
                text = stringResource(R.string.settings_weekly_goal_label, uiState.goals.weeklyGoalSteps.toString()),
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
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_advanced_section_title), style = MaterialTheme.typography.titleMedium)
                Switch(checked = expanded, onCheckedChange = { expanded = it })
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
 * "Permission status" reflects source availability only (permission granted, Play services
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
            // "waiting" (nothing observed yet) or "has been observed" (historical evidence, not a
            // claim about this exact moment).
            StepCollectionHealth.WAITING_FOR_FIRST_SAMPLE -> stringResource(R.string.status_waiting_for_first_sample)
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
 * [com.example.stepsplit.data.stepsource.LocalRecordingStepSource] - "Run step source check" below
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
            DebugDiagnosticRow(
                R.string.settings_debug_label_play_services_version,
                "${device.playServicesInstalledVersionName ?: "?"} (${device.playServicesInstalledVersionCode ?: "?"}) / ${device.playServicesRequiredMinVersionCode}",
            )
            DebugDiagnosticRow(
                R.string.settings_debug_label_step_sensors,
                "${device.hasStepCounterSensor} / ${device.hasStepDetectorSensor}",
            )
        }

        ValidationDiagnosticsSection(uiState)
    }
}

/**
 * Strict vehicle-aware validation acquisition diagnostics - debug-only, same reasoning as
 * [DebugDiagnosticsSection] above. Reads [SettingsUiState.motionDiagnostics]
 * ([com.example.stepsplit.data.motion.MotionDiagnosticsStore]'s persisted state, populated
 * exclusively by real registration/validation outcomes - see that class's own doc comment) plus
 * live [SettingsUiState.validationStateCounts] and [SettingsUiState.recentMotionEvidence]. Purely
 * a read surface: nothing here can suspend, block, or otherwise affect step acquisition/validation
 * itself.
 */
@Composable
private fun ValidationDiagnosticsSection(uiState: SettingsUiState) {
    val motion = uiState.motionDiagnostics
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_debug_validation_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        DebugDiagnosticRow(R.string.settings_debug_label_policy_version, "${uiState.currentValidationPolicyVersion}")
        DebugDiagnosticRow(R.string.settings_debug_label_transition_registration, debugRegistrationText(motion.latestTransitionRegistrationSucceeded, motion.latestTransitionFailureCategory, motion.latestTransitionFailureStatusCode, motion.latestTransitionRegistrationAtEpochSecond))
        DebugDiagnosticRow(R.string.settings_debug_label_sampling_registration, debugRegistrationText(motion.latestSamplingRegistrationSucceeded, motion.latestSamplingFailureCategory, motion.latestSamplingFailureStatusCode, motion.latestSamplingRegistrationAtEpochSecond))
        DebugDiagnosticRow(R.string.settings_debug_label_last_validation, debugTimestampText(motion.latestSuccessfulValidationAtEpochSecond))
        DebugDiagnosticRow(
            R.string.settings_debug_label_validation_counts,
            listOf("PENDING", "ACCEPTED_WALKING", "ACCEPTED_RUNNING", "REJECTED_VEHICLE", "REJECTED_BICYCLE", "REJECTED_UNVERIFIED", "LEGACY_UNVERIFIED")
                .joinToString(", ") { "$it=${uiState.validationStateCounts[it] ?: 0}" },
        )
        if (uiState.recentMotionEvidence.isNotEmpty()) {
            Text(
                stringResource(R.string.settings_debug_recent_events_title),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            uiState.recentMotionEvidence.forEach { evidence ->
                Text(
                    text = "${evidence.kind} ${evidence.activityType}" +
                        (evidence.confidence?.let { " ($it%)" } ?: "") +
                        " @ ${debugTimestampText(evidence.derivedWallClockEpochMilli / 1000)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** "-" when never attempted; otherwise the latest transition/sampling registration outcome - see [com.example.stepsplit.data.motion.MotionDiagnosticsSnapshot]'s own doc comment for why the two are tracked separately. */
@Composable
private fun debugRegistrationText(succeeded: Boolean?, failureCategory: String?, failureStatusCode: Int?, atEpochSecond: Long?): String {
    val at = debugTimestampText(atEpochSecond)
    return when (succeeded) {
        true -> "OK @ $at"
        false -> "FAILED ($failureCategory, code=$failureStatusCode) @ $at"
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

@Composable
private fun debugSubscriptionText(health: StepSourceHealthSnapshot): String {
    val at = debugTimestampText(health.latestSubscriptionAtEpochSecond)
    return when (health.latestSubscriptionSucceeded) {
        true -> "OK @ $at"
        false -> "FAILED (${health.latestSubscriptionFailureCategory}, code=${health.latestSubscriptionFailureStatusCode}) @ $at"
        null -> "-"
    }
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
