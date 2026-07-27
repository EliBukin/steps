package com.example.stepsplit.ui.settings

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
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.ui.common.statusText

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onSetDailyGoal: (Long) -> Unit,
    onSetThresholds: (ClassificationThresholds) -> Unit,
    onResetThresholds: () -> Unit,
    onGenerateSampleData: () -> Unit,
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
                SettingsSection.Debug -> if (BuildConfig.DEBUG) DebugSection(onGenerateSampleData)
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
                text = uiState.availability?.statusText() ?: stringResource(R.string.status_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DebugSection(onGenerateSampleData: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_debug_section_title), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = onGenerateSampleData) {
                Text(stringResource(R.string.settings_debug_seed_action))
            }
        }
    }
}
