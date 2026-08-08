package com.example.stepsplit.data.motion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.motionDiagnosticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "stepsplit_motion_diagnostics")

/** Sanitized detail behind a failed transition/sampling registration attempt - never a raw exception message. */
data class MotionRegistrationFailure(val category: String, val statusCode: Int?)

/**
 * A point-in-time view of motion-evidence acquisition health - registration status/last error for
 * transitions and sampling tracked *separately* (a transition-only failure must never be hidden
 * behind a healthy sampling registration, or vice versa), last successful validation time, and
 * whether AR permission was ever confirmed granted. Mirrors
 * [com.example.stepsplit.data.stepsource.StepSourceHealthSnapshot]'s own reasoning for existing as a
 * distinct, persisted concern.
 */
data class MotionDiagnosticsSnapshot(
    val latestTransitionRegistrationAtEpochSecond: Long? = null,
    val latestTransitionRegistrationSucceeded: Boolean? = null,
    val latestTransitionFailureCategory: String? = null,
    val latestTransitionFailureStatusCode: Int? = null,
    val latestSamplingRegistrationAtEpochSecond: Long? = null,
    val latestSamplingRegistrationSucceeded: Boolean? = null,
    val latestSamplingFailureCategory: String? = null,
    val latestSamplingFailureStatusCode: Int? = null,
    val latestSuccessfulValidationAtEpochSecond: Long? = null,
)

/** Suspending half - real DataStore persistence. See [MotionDiagnosticsHealthSink] for why acquisition depends on a different, non-suspending contract instead. */
interface MotionDiagnosticsHealthRecorder {
    suspend fun recordTransitionRegistrationSuccess(atEpochSecond: Long)
    suspend fun recordTransitionRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long)
    suspend fun recordSamplingRegistrationSuccess(atEpochSecond: Long)
    suspend fun recordSamplingRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long)
    suspend fun recordSuccessfulValidation(atEpochSecond: Long)
}

/**
 * Non-suspending half [MotionEvidenceRegistrar]/the ingestion path depend on directly - see
 * [com.example.stepsplit.data.stepsource.StepSourceHealthSink]'s own doc comment for the identical
 * reasoning: a diagnostic side-channel must never be able to gate or delay real registration/evidence
 * processing by suspending on persistence.
 */
interface MotionDiagnosticsHealthSink {
    fun recordTransitionRegistrationSuccess(atEpochSecond: Long)
    fun recordTransitionRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long)
    fun recordSamplingRegistrationSuccess(atEpochSecond: Long)
    fun recordSamplingRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long)
    fun recordSuccessfulValidation(atEpochSecond: Long)
}

/** No-op default - lets [com.example.stepsplit.data.repository.StepRepository] be constructed (e.g. in tests) without a diagnostics dependency, matching the same "trivial default is safe here because it's non-suspending" reasoning as [com.example.stepsplit.data.stepsource.StepSourceHealthSink]'s own production Async wrapper. */
object NoOpMotionDiagnosticsHealthSink : MotionDiagnosticsHealthSink {
    override fun recordTransitionRegistrationSuccess(atEpochSecond: Long) = Unit
    override fun recordTransitionRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long) = Unit
    override fun recordSamplingRegistrationSuccess(atEpochSecond: Long) = Unit
    override fun recordSamplingRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long) = Unit
    override fun recordSuccessfulValidation(atEpochSecond: Long) = Unit
}

/** Persists motion-evidence acquisition diagnostics - a separate Preferences DataStore file from every other store, same reasoning as [com.example.stepsplit.data.stepsource.StepSourceHealthStore]. */
class MotionDiagnosticsStore(context: Context) : MotionDiagnosticsHealthRecorder {

    private val dataStore = context.motionDiagnosticsDataStore

    private object Keys {
        val LATEST_TRANSITION_REGISTRATION_AT = longPreferencesKey("latest_transition_registration_at")
        val LATEST_TRANSITION_REGISTRATION_SUCCEEDED = booleanPreferencesKey("latest_transition_registration_succeeded")
        val LATEST_TRANSITION_FAILURE_CATEGORY = stringPreferencesKey("latest_transition_failure_category")
        val LATEST_TRANSITION_FAILURE_STATUS_CODE = intPreferencesKey("latest_transition_failure_status_code")
        val LATEST_SAMPLING_REGISTRATION_AT = longPreferencesKey("latest_sampling_registration_at")
        val LATEST_SAMPLING_REGISTRATION_SUCCEEDED = booleanPreferencesKey("latest_sampling_registration_succeeded")
        val LATEST_SAMPLING_FAILURE_CATEGORY = stringPreferencesKey("latest_sampling_failure_category")
        val LATEST_SAMPLING_FAILURE_STATUS_CODE = intPreferencesKey("latest_sampling_failure_status_code")
        val LATEST_SUCCESSFUL_VALIDATION_AT = longPreferencesKey("latest_successful_validation_at")
    }

    val snapshot: Flow<MotionDiagnosticsSnapshot> = dataStore.data.map { prefs ->
        MotionDiagnosticsSnapshot(
            latestTransitionRegistrationAtEpochSecond = prefs[Keys.LATEST_TRANSITION_REGISTRATION_AT],
            latestTransitionRegistrationSucceeded = prefs[Keys.LATEST_TRANSITION_REGISTRATION_SUCCEEDED],
            latestTransitionFailureCategory = prefs[Keys.LATEST_TRANSITION_FAILURE_CATEGORY],
            latestTransitionFailureStatusCode = prefs[Keys.LATEST_TRANSITION_FAILURE_STATUS_CODE],
            latestSamplingRegistrationAtEpochSecond = prefs[Keys.LATEST_SAMPLING_REGISTRATION_AT],
            latestSamplingRegistrationSucceeded = prefs[Keys.LATEST_SAMPLING_REGISTRATION_SUCCEEDED],
            latestSamplingFailureCategory = prefs[Keys.LATEST_SAMPLING_FAILURE_CATEGORY],
            latestSamplingFailureStatusCode = prefs[Keys.LATEST_SAMPLING_FAILURE_STATUS_CODE],
            latestSuccessfulValidationAtEpochSecond = prefs[Keys.LATEST_SUCCESSFUL_VALIDATION_AT],
        )
    }

    override suspend fun recordTransitionRegistrationSuccess(atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_TRANSITION_REGISTRATION_AT] = atEpochSecond
            prefs[Keys.LATEST_TRANSITION_REGISTRATION_SUCCEEDED] = true
            prefs.remove(Keys.LATEST_TRANSITION_FAILURE_CATEGORY)
            prefs.remove(Keys.LATEST_TRANSITION_FAILURE_STATUS_CODE)
        }
    }

    override suspend fun recordTransitionRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_TRANSITION_REGISTRATION_AT] = atEpochSecond
            prefs[Keys.LATEST_TRANSITION_REGISTRATION_SUCCEEDED] = false
            prefs[Keys.LATEST_TRANSITION_FAILURE_CATEGORY] = failure.category
            if (failure.statusCode != null) prefs[Keys.LATEST_TRANSITION_FAILURE_STATUS_CODE] = failure.statusCode else prefs.remove(Keys.LATEST_TRANSITION_FAILURE_STATUS_CODE)
        }
    }

    override suspend fun recordSamplingRegistrationSuccess(atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_SAMPLING_REGISTRATION_AT] = atEpochSecond
            prefs[Keys.LATEST_SAMPLING_REGISTRATION_SUCCEEDED] = true
            prefs.remove(Keys.LATEST_SAMPLING_FAILURE_CATEGORY)
            prefs.remove(Keys.LATEST_SAMPLING_FAILURE_STATUS_CODE)
        }
    }

    override suspend fun recordSamplingRegistrationFailure(failure: MotionRegistrationFailure, atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_SAMPLING_REGISTRATION_AT] = atEpochSecond
            prefs[Keys.LATEST_SAMPLING_REGISTRATION_SUCCEEDED] = false
            prefs[Keys.LATEST_SAMPLING_FAILURE_CATEGORY] = failure.category
            if (failure.statusCode != null) prefs[Keys.LATEST_SAMPLING_FAILURE_STATUS_CODE] = failure.statusCode else prefs.remove(Keys.LATEST_SAMPLING_FAILURE_STATUS_CODE)
        }
    }

    override suspend fun recordSuccessfulValidation(atEpochSecond: Long) {
        dataStore.edit { prefs -> prefs[Keys.LATEST_SUCCESSFUL_VALIDATION_AT] = atEpochSecond }
    }

    /** Test isolation - see [com.example.stepsplit.data.stepsource.StepSourceHealthStore.clear]'s own doc comment for why. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
