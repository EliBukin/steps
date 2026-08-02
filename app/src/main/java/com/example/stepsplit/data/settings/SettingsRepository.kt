package com.example.stepsplit.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepGoals
import com.example.stepsplit.domain.model.SyncFailure
import com.example.stepsplit.domain.model.SyncFailureCategory
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "stepsplit_settings")

data class AppSettings(
    val goals: StepGoals,
    val thresholds: ClassificationThresholds,
    val lastSuccessfulSync: Instant?,
    /** The most recent sync failure, if any hasn't since been cleared by a successful sync - see [SyncFailure]. */
    val lastSyncFailure: SyncFailure?,
)

/**
 * Small key-value application settings: the user's daily step goal, the classification
 * thresholds (advanced/optional tuning), and the last successful sync timestamp. Everything here
 * is a scalar preference, so Preferences DataStore (not proto DataStore or Room) is the right
 * fit - no schema migration machinery needed for a handful of numbers.
 */
class SettingsRepository(context: Context) {

    private val dataStore = context.settingsDataStore

    private object Keys {
        val DAILY_GOAL = longPreferencesKey("daily_goal_steps")
        val MAX_GAP_MINUTES = intPreferencesKey("threshold_max_gap_minutes")
        val IDLE_FINALIZE_MINUTES = intPreferencesKey("threshold_idle_finalize_minutes")
        val MIN_DURATION_MINUTES = intPreferencesKey("threshold_min_duration_minutes")
        val MIN_ACTIVE_MINUTES = intPreferencesKey("threshold_min_active_minutes")
        val MIN_STEPS = longPreferencesKey("threshold_min_steps")
        val MIN_CADENCE = doublePreferencesKey("threshold_min_cadence")
        val LAST_SYNC_EPOCH_SECOND = longPreferencesKey("last_sync_epoch_second")
        val SYNC_FAILURE_CATEGORY = stringPreferencesKey("sync_failure_category")
        val SYNC_FAILURE_EPOCH_SECOND = longPreferencesKey("sync_failure_epoch_second")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val default = ClassificationThresholds.DEFAULT
        AppSettings(
            goals = StepGoals(
                dailyGoalSteps = prefs[Keys.DAILY_GOAL] ?: StepGoals.DEFAULT_DAILY_GOAL,
            ),
            thresholds = ClassificationThresholds(
                maxGapMinutes = prefs[Keys.MAX_GAP_MINUTES] ?: default.maxGapMinutes,
                idleFinalizeMinutes = prefs[Keys.IDLE_FINALIZE_MINUTES] ?: default.idleFinalizeMinutes,
                minBoutDurationMinutes = prefs[Keys.MIN_DURATION_MINUTES] ?: default.minBoutDurationMinutes,
                minActiveMinutes = prefs[Keys.MIN_ACTIVE_MINUTES] ?: default.minActiveMinutes,
                minSteps = prefs[Keys.MIN_STEPS] ?: default.minSteps,
                minCadenceStepsPerMinute = prefs[Keys.MIN_CADENCE] ?: default.minCadenceStepsPerMinute,
            ),
            lastSuccessfulSync = prefs[Keys.LAST_SYNC_EPOCH_SECOND]?.let { Instant.ofEpochSecond(it) },
            lastSyncFailure = prefs[Keys.SYNC_FAILURE_CATEGORY]?.let { categoryName ->
                val atEpochSecond = prefs[Keys.SYNC_FAILURE_EPOCH_SECOND]
                if (atEpochSecond == null) {
                    null
                } else {
                    SyncFailure(SyncFailureCategory.valueOf(categoryName), atEpochSecond)
                }
            },
        )
    }

    /** Rejects invalid input rather than persisting it, so a bad goal can never be read back later. */
    suspend fun setDailyGoal(steps: Long): Boolean {
        if (!StepGoals.isValidDailyGoal(steps)) return false
        dataStore.edit { it[Keys.DAILY_GOAL] = steps }
        return true
    }

    suspend fun setThresholds(thresholds: ClassificationThresholds): Boolean {
        if (!thresholds.isValid()) return false
        dataStore.edit { prefs ->
            prefs[Keys.MAX_GAP_MINUTES] = thresholds.maxGapMinutes
            prefs[Keys.IDLE_FINALIZE_MINUTES] = thresholds.idleFinalizeMinutes
            prefs[Keys.MIN_DURATION_MINUTES] = thresholds.minBoutDurationMinutes
            prefs[Keys.MIN_ACTIVE_MINUTES] = thresholds.minActiveMinutes
            prefs[Keys.MIN_STEPS] = thresholds.minSteps
            prefs[Keys.MIN_CADENCE] = thresholds.minCadenceStepsPerMinute
        }
        return true
    }

    suspend fun resetThresholds() {
        setThresholds(ClassificationThresholds.DEFAULT)
    }

    suspend fun setLastSuccessfulSync(instant: Instant) {
        dataStore.edit { it[Keys.LAST_SYNC_EPOCH_SECOND] = instant.epochSecond }
    }

    /**
     * Persisted (not just in-memory ViewModel state) so a failure that happens during a
     * background WorkManager sync is still visible the next time the app is opened, not only
     * during the ViewModel call that happened to trigger it.
     */
    suspend fun recordSyncFailure(failure: SyncFailure) {
        dataStore.edit { prefs ->
            prefs[Keys.SYNC_FAILURE_CATEGORY] = failure.category.name
            prefs[Keys.SYNC_FAILURE_EPOCH_SECOND] = failure.atEpochSecond
        }
    }

    /** Called after a genuinely successful sync - a failure must never keep showing once collection is working again. */
    suspend fun clearSyncFailure() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.SYNC_FAILURE_CATEGORY)
            prefs.remove(Keys.SYNC_FAILURE_EPOCH_SECOND)
        }
    }
}
