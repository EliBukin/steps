package com.example.stepsplit.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.stepsplit.R
import com.example.stepsplit.data.stepsource.StepSourceAvailability
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.ClassificationReasonCode
import com.example.stepsplit.domain.model.SyncFailureCategory

@StringRes
private fun ClassificationReasonCode.textRes(): Int = when (this) {
    ClassificationReasonCode.MEETS_ALL_THRESHOLDS -> R.string.reason_meets_all_thresholds
    ClassificationReasonCode.DURATION_TOO_SHORT -> R.string.reason_duration_too_short
    ClassificationReasonCode.TOO_FEW_ACTIVE_MINUTES -> R.string.reason_too_few_active_minutes
    ClassificationReasonCode.TOO_FEW_STEPS -> R.string.reason_too_few_steps
    ClassificationReasonCode.CADENCE_TOO_LOW -> R.string.reason_cadence_too_low
    ClassificationReasonCode.MULTIPLE_THRESHOLDS_NOT_MET -> R.string.reason_multiple_not_met
    ClassificationReasonCode.MANUALLY_RECLASSIFIED -> R.string.reason_manually_reclassified
}

@Composable
fun ClassificationReasonCode.text(): String = stringResource(textRes())

@Composable
fun BoutClassification.text(): String = when (this) {
    BoutClassification.WORKOUT -> stringResource(R.string.session_classification_workout)
    BoutClassification.INCIDENTAL -> stringResource(R.string.session_classification_incidental)
}

@Composable
fun StepSourceAvailability.statusText(): String = when (this) {
    StepSourceAvailability.Available -> stringResource(R.string.status_available)
    StepSourceAvailability.PermissionNotGranted -> stringResource(R.string.status_permission_not_granted)
    StepSourceAvailability.PlayServicesUpdateRequired -> stringResource(R.string.status_play_services_update_required)
    StepSourceAvailability.ApiUnavailable -> stringResource(R.string.status_api_unavailable)
    is StepSourceAvailability.Error -> stringResource(R.string.status_error, message)
}

/** Structured, localized category text - never the raw exception message a failure was recorded with. */
@Composable
fun SyncFailureCategory.text(): String = when (this) {
    SyncFailureCategory.SUBSCRIPTION_FAILED -> stringResource(R.string.sync_failure_subscription_message)
    SyncFailureCategory.READ_FAILED -> stringResource(R.string.sync_failure_read_message)
    SyncFailureCategory.UNKNOWN -> stringResource(R.string.sync_failure_unknown_message)
}
