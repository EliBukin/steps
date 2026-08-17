package com.example.stepsplit.data.stepsource

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

private val Context.stepSourceHealthDataStore: DataStore<Preferences> by preferencesDataStore(name = "stepsplit_step_source_health")

/**
 * A point-in-time view of production step acquisition health - see [StepSourceHealthStore]'s own
 * doc comment for why this exists as a concern separate from [StepSourceAvailability] and
 * [com.example.stepsplit.domain.model.SyncFailure]. Every timestamp is an epoch second; every
 * failure category/status code is sanitized (see [ApiFailure]) - nothing here is a raw exception
 * message.
 */
data class StepSourceHealthSnapshot(
    /** True the moment a single raw interval with a positive step count has ever been read from the source - monotonic, never reset back to false. */
    val everObservedSample: Boolean = false,
    val latestSampleEpochSecond: Long? = null,
    val latestReadAttemptEpochSecond: Long? = null,
    /** The `[fromInclusive, toExclusive)` window actually requested by the read attempt recorded in [latestReadAttemptEpochSecond] - debug-only, so a real-device diagnostic session can tell "the read failed/came back empty" apart from "the read only ever asked for a window that could never have contained new steps". */
    val latestRequestedWindowStartEpochSecond: Long? = null,
    val latestRequestedWindowEndEpochSecond: Long? = null,
    val latestSuccessfulReadEpochSecond: Long? = null,
    /** Raw interval count from the most recent SUCCESSFUL read (null before any successful read - never set by a failed attempt). */
    val latestReadIntervalCount: Int? = null,
    /** Consecutive successful reads in a row that returned zero raw intervals - reset to 0 the moment a read returns at least one. */
    val consecutiveEmptyReads: Int = 0,
    val latestSubscriptionAtEpochSecond: Long? = null,
    val latestSubscriptionSucceeded: Boolean? = null,
    val latestSubscriptionFailureCategory: ApiFailureCategory? = null,
    val latestSubscriptionFailureStatusCode: Int? = null,
    val latestReadFailureAtEpochSecond: Long? = null,
    val latestReadFailureCategory: ApiFailureCategory? = null,
    val latestReadFailureStatusCode: Int? = null,
)

/**
 * The set of health-recording operations real diagnostic *persistence* performs after every
 * subscribe/read attempt - suspend because genuine persistence (a Preferences DataStore write, in
 * [StepSourceHealthStore] below) genuinely needs to suspend. This is deliberately NOT what
 * [HealthConnectStepSource] depends on directly - see [StepSourceHealthSink]'s own doc comment for
 * why the acquisition side needs a structurally different, non-suspending contract instead. This
 * interface is instead the contract [AsyncStepSourceHealthRecorder] speaks *to* (as its own
 * `delegate`), and the one real persistence implementations like [StepSourceHealthStore] implement.
 */
interface StepSourceHealthRecorder {
    suspend fun recordSubscriptionSuccess(atEpochSecond: Long)
    suspend fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long)
    suspend fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long)
    suspend fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long)
    suspend fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long)
}

/**
 * The set of health-recording operations [HealthConnectStepSource] itself depends on -
 * deliberately NON-suspending, unlike [StepSourceHealthRecorder] above. This is what "encodes the
 * acquisition-side non-blocking guarantee" at the type level rather than just by convention: a
 * `suspend fun` COULD always be implemented by suspending on real, potentially-slow persistence (as
 * [StepSourceHealthStore] legitimately does) - there is nothing in a `suspend` signature itself that
 * rules that out, which is exactly how the source this app used before Health Connect
 * (`LocalRecordingStepSource`) used to accept `StepSourceHealthStore(context)` directly as an
 * unsafe default. A plain, non-suspend `fun` cannot
 * naturally do that: writing a blocking implementation now requires visibly fighting the type
 * (`runBlocking` or similar), not just implementing the interface normally, so the type itself is
 * the safeguard against ever reintroducing that bug by accident. [AsyncStepSourceHealthRecorder] is
 * the only production implementation - every method here does nothing but a non-suspending
 * [kotlinx.coroutines.channels.Channel.trySend], see its own doc comment.
 */
interface StepSourceHealthSink {
    fun recordSubscriptionSuccess(atEpochSecond: Long)
    fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long)
    fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long)
    fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long)
    fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long)
}

/**
 * Persists production Local Recording acquisition health - orthogonal to both
 * [StepSourceAvailability] (permission/API presence) and
 * [com.example.stepsplit.domain.model.SyncFailure] (the repository's own sync-pipeline outcome).
 * Neither of those can distinguish "subscribed and reading successfully, but zero steps ever
 * observed" from "genuinely collecting real step data" - a successful empty read looks identical
 * to a healthy one under both. This store closes that gap by recording what the source itself
 * actually saw, so the UI can show an honest "waiting for first step data" state (see
 * [com.example.stepsplit.domain.model.deriveStepCollectionHealth]) instead of a false "active".
 *
 * A separate Preferences DataStore file from [com.example.stepsplit.data.settings.SettingsRepository]
 * deliberately - this is acquisition diagnostics, not a user-facing app setting, and keeping it
 * separate means a future "reset app settings" action can't accidentally wipe out (or be
 * complicated by) health history, and vice versa.
 */
class StepSourceHealthStore(context: Context) : StepSourceHealthRecorder {

    private val dataStore = context.stepSourceHealthDataStore

    private object Keys {
        val EVER_OBSERVED_SAMPLE = booleanPreferencesKey("ever_observed_sample")
        val LATEST_SAMPLE_EPOCH_SECOND = longPreferencesKey("latest_sample_epoch_second")
        val LATEST_READ_ATTEMPT_EPOCH_SECOND = longPreferencesKey("latest_read_attempt_epoch_second")
        val LATEST_REQUESTED_WINDOW_START_EPOCH_SECOND = longPreferencesKey("latest_requested_window_start_epoch_second")
        val LATEST_REQUESTED_WINDOW_END_EPOCH_SECOND = longPreferencesKey("latest_requested_window_end_epoch_second")
        val LATEST_SUCCESSFUL_READ_EPOCH_SECOND = longPreferencesKey("latest_successful_read_epoch_second")
        val LATEST_READ_INTERVAL_COUNT = intPreferencesKey("latest_read_interval_count")
        val CONSECUTIVE_EMPTY_READS = intPreferencesKey("consecutive_empty_reads")
        val LATEST_SUBSCRIPTION_AT_EPOCH_SECOND = longPreferencesKey("latest_subscription_at_epoch_second")
        val LATEST_SUBSCRIPTION_SUCCEEDED = booleanPreferencesKey("latest_subscription_succeeded")
        val LATEST_SUBSCRIPTION_FAILURE_CATEGORY = stringPreferencesKey("latest_subscription_failure_category")
        val LATEST_SUBSCRIPTION_FAILURE_STATUS_CODE = intPreferencesKey("latest_subscription_failure_status_code")
        val LATEST_READ_FAILURE_AT_EPOCH_SECOND = longPreferencesKey("latest_read_failure_at_epoch_second")
        val LATEST_READ_FAILURE_CATEGORY = stringPreferencesKey("latest_read_failure_category")
        val LATEST_READ_FAILURE_STATUS_CODE = intPreferencesKey("latest_read_failure_status_code")
    }

    val snapshot: Flow<StepSourceHealthSnapshot> = dataStore.data.map { prefs ->
        StepSourceHealthSnapshot(
            everObservedSample = prefs[Keys.EVER_OBSERVED_SAMPLE] ?: false,
            latestSampleEpochSecond = prefs[Keys.LATEST_SAMPLE_EPOCH_SECOND],
            latestReadAttemptEpochSecond = prefs[Keys.LATEST_READ_ATTEMPT_EPOCH_SECOND],
            latestRequestedWindowStartEpochSecond = prefs[Keys.LATEST_REQUESTED_WINDOW_START_EPOCH_SECOND],
            latestRequestedWindowEndEpochSecond = prefs[Keys.LATEST_REQUESTED_WINDOW_END_EPOCH_SECOND],
            latestSuccessfulReadEpochSecond = prefs[Keys.LATEST_SUCCESSFUL_READ_EPOCH_SECOND],
            latestReadIntervalCount = prefs[Keys.LATEST_READ_INTERVAL_COUNT],
            consecutiveEmptyReads = prefs[Keys.CONSECUTIVE_EMPTY_READS] ?: 0,
            latestSubscriptionAtEpochSecond = prefs[Keys.LATEST_SUBSCRIPTION_AT_EPOCH_SECOND],
            latestSubscriptionSucceeded = prefs[Keys.LATEST_SUBSCRIPTION_SUCCEEDED],
            latestSubscriptionFailureCategory = prefs[Keys.LATEST_SUBSCRIPTION_FAILURE_CATEGORY]?.let { ApiFailureCategory.valueOf(it) },
            latestSubscriptionFailureStatusCode = prefs[Keys.LATEST_SUBSCRIPTION_FAILURE_STATUS_CODE],
            latestReadFailureAtEpochSecond = prefs[Keys.LATEST_READ_FAILURE_AT_EPOCH_SECOND],
            latestReadFailureCategory = prefs[Keys.LATEST_READ_FAILURE_CATEGORY]?.let { ApiFailureCategory.valueOf(it) },
            latestReadFailureStatusCode = prefs[Keys.LATEST_READ_FAILURE_STATUS_CODE],
        )
    }

    override suspend fun recordSubscriptionSuccess(atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_SUBSCRIPTION_AT_EPOCH_SECOND] = atEpochSecond
            prefs[Keys.LATEST_SUBSCRIPTION_SUCCEEDED] = true
            prefs.remove(Keys.LATEST_SUBSCRIPTION_FAILURE_CATEGORY)
            prefs.remove(Keys.LATEST_SUBSCRIPTION_FAILURE_STATUS_CODE)
        }
    }

    override suspend fun recordSubscriptionFailure(failure: ApiFailure, atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_SUBSCRIPTION_AT_EPOCH_SECOND] = atEpochSecond
            prefs[Keys.LATEST_SUBSCRIPTION_SUCCEEDED] = false
            prefs[Keys.LATEST_SUBSCRIPTION_FAILURE_CATEGORY] = failure.category.name
            if (failure.statusCode != null) {
                prefs[Keys.LATEST_SUBSCRIPTION_FAILURE_STATUS_CODE] = failure.statusCode
            } else {
                prefs.remove(Keys.LATEST_SUBSCRIPTION_FAILURE_STATUS_CODE)
            }
        }
    }

    override suspend fun recordReadAttempt(atEpochSecond: Long, windowStartEpochSecond: Long, windowEndEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_READ_ATTEMPT_EPOCH_SECOND] = atEpochSecond
            prefs[Keys.LATEST_REQUESTED_WINDOW_START_EPOCH_SECOND] = windowStartEpochSecond
            prefs[Keys.LATEST_REQUESTED_WINDOW_END_EPOCH_SECOND] = windowEndEpochSecond
        }
    }

    /**
     * Records a successful read (the underlying response status was itself success - see
     * [toRawIntervalsOrThrow]), whether or not it returned any positive samples.
     * [latestSampleEpochSecond] is this read's own latest positive sample end time, or null for a
     * genuinely empty read - never conflated with "no attempt happened yet" ([latestReadAttemptEpochSecond]
     * covers that).
     *
     * Deliberately does NOT touch [Keys.LATEST_READ_FAILURE_AT_EPOCH_SECOND]/`_CATEGORY`/`_STATUS_CODE`
     * - those are "last failure ever" fields (see [recordReadFailure]'s own doc comment): a later
     * success is genuinely useful evidence ("it's working again *now*"), but it is not proof the
     * earlier failure didn't happen, so the failure record must survive it. Both signals are shown
     * together in the debug panel (latest success timestamp alongside the last-ever failure), never
     * one silently erasing the other.
     */
    override suspend fun recordReadSuccess(intervalCount: Int, latestSampleEpochSecond: Long?, atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_SUCCESSFUL_READ_EPOCH_SECOND] = atEpochSecond
            prefs[Keys.LATEST_READ_INTERVAL_COUNT] = intervalCount
            if (intervalCount > 0) {
                prefs[Keys.CONSECUTIVE_EMPTY_READS] = 0
                prefs[Keys.EVER_OBSERVED_SAMPLE] = true
                if (latestSampleEpochSecond != null) prefs[Keys.LATEST_SAMPLE_EPOCH_SECOND] = latestSampleEpochSecond
            } else {
                prefs[Keys.CONSECUTIVE_EMPTY_READS] = (prefs[Keys.CONSECUTIVE_EMPTY_READS] ?: 0) + 1
            }
        }
    }

    /**
     * Clears all recorded health state. Primarily for test isolation - Robolectric does not
     * guarantee a fresh Preferences DataStore file per test method (the same caveat already
     * documented on [com.example.stepsplit.data.repository.StepRepositoryTest]'s own teardown) -
     * but also a reasonable future debug "reset diagnostics" action.
     */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /**
     * "Last failure ever" semantics: [atEpochSecond]/[failure] here always overwrite the previous
     * failure record (a newer failure is more relevant than an older one), but - unlike every other
     * field in this store - are never cleared by a later *success* (see [recordReadSuccess]'s own
     * doc comment). A real-device investigation needs to see "there was a read failure, here's
     * exactly what it was and when" even after the very next sync happens to succeed; silently
     * wiping that the moment things start working again would hide the most useful diagnostic
     * evidence right when a developer is most likely to be looking at it.
     *
     * Deliberately does NOT touch LATEST_READ_INTERVAL_COUNT or CONSECUTIVE_EMPTY_READS - a failed
     * read is not evidence of a zero-sample read (see [recordReadSuccess]'s own doc comment); those
     * fields only ever reflect a completed, successful read.
     */
    override suspend fun recordReadFailure(failure: ApiFailure, atEpochSecond: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.LATEST_READ_FAILURE_AT_EPOCH_SECOND] = atEpochSecond
            prefs[Keys.LATEST_READ_FAILURE_CATEGORY] = failure.category.name
            if (failure.statusCode != null) {
                prefs[Keys.LATEST_READ_FAILURE_STATUS_CODE] = failure.statusCode
            } else {
                prefs.remove(Keys.LATEST_READ_FAILURE_STATUS_CODE)
            }
        }
    }
}
