package com.example.stepsplit.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.settings.resetCorruptPreferences
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.stepsource.StepSourceHealthStore
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.classification.CLASSIFIER_VERSION
import com.example.stepsplit.domain.model.TripState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Names stored by an older or newer version of the app that this one doesn't know - the categories
 * `ApiFailureCategory` lost in an earlier version are real examples. Reading one must not throw:
 * the settings and diagnostics are read on every launch, so a throw there crashed the app every
 * time. Each store is written with such a name, then read through the app's own code.
 */
@RunWith(RobolectricTestRunner::class)
class UnknownStoredNamesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val now = Instant.parse("2026-03-10T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: StepSplitDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StepSplitDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    /** A preferences store of the app's kind, in a file of its own. */
    private fun preferences(name: String) =
        PreferenceDataStoreFactory.create(corruptionHandler = resetCorruptPreferences, scope = scope) { File(folder.root, "$name.preferences_pb") }

    @Test
    fun `an unknown sync failure name reads as no failure, and the next sync replaces it`() = runBlocking {
        val store = preferences("stepsplit_settings")
        store.edit {
            it[stringPreferencesKey("sync_failure_category")] = "RECORDING_API_GONE"
            it[longPreferencesKey("sync_failure_epoch_second")] = 5L
            it[longPreferencesKey("daily_goal_steps")] = 12_000L
        }
        val settings = SettingsRepository(store)

        val read = settings.settings.first()
        assertNull(read.lastSyncFailure)
        assertEquals("the rest still reads", 12_000L, read.goals.dailyGoalSteps)

        settings.recordSuccessfulSync(now)
        assertNull(store.data.first()[stringPreferencesKey("sync_failure_category")])
    }

    @Test
    fun `unknown failure names in the diagnostics read as none, and the next record drops them`() = runBlocking {
        val store = preferences("stepsplit_step_source_health")
        store.edit {
            it[stringPreferencesKey("latest_subscription_failure_category")] = "SUBSCRIPTION_INVALID"
            it[stringPreferencesKey("latest_read_failure_category")] = "DATA_SOURCE_UNAVAILABLE"
            it[longPreferencesKey("latest_read_failure_at_epoch_second")] = 5L
        }
        val health = StepSourceHealthStore(store)

        val snapshot = health.snapshot.first()
        assertNull(snapshot.latestSubscriptionFailureCategory)
        assertNull(snapshot.latestReadFailureCategory)
        assertEquals("the rest still reads", 5L, snapshot.latestReadFailureAtEpochSecond)

        health.recordReadSuccess(intervalCount = 3, latestSampleEpochSecond = 100L, atEpochSecond = 10L)
        val stored = store.data.first()
        assertNull(stored[stringPreferencesKey("latest_subscription_failure_category")])
        assertNull(stored[stringPreferencesKey("latest_read_failure_category")])
    }

    @Test
    fun `a trip in a state this version doesn't know shows as a finished trip`() = runBlocking {
        database.tripDao().insert(
            TripEntity(
                startEpochSecond = now.epochSecond - 600,
                endEpochSecond = now.epochSecond,
                startZoneId = "UTC",
                state = "PAUSED",
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = now.epochSecond - 600,
            ),
        )

        assertEquals(TripState.FINISHED, TripRepository(database, clock).observeTrips().first().single().state)
    }

    @Test
    fun `a walk with a label this version doesn't know counts as incidental`() = runBlocking {
        val start = now.epochSecond - 3_600
        database.stepBucketDao().upsertAll(
            listOf(
                StepBucketEntity(
                    source = "fake",
                    startEpochSecond = start,
                    endEpochSecond = start + 60,
                    steps = 90,
                    zoneId = "UTC",
                    localDate = "2026-03-10",
                    importedAtEpochSecond = start,
                ),
            ),
        )
        database.walkBoutDao().insertAll(
            listOf(
                WalkBoutEntity(
                    startEpochSecond = start,
                    endEpochSecond = start + 60,
                    steps = 90,
                    activeMinutes = 1,
                    elapsedMinutes = 1,
                    cadence = 90.0,
                    autoClassification = "BRISK_WALK",
                    autoConfidence = 0.9,
                    autoReasonCode = "MEETS_ALL_THRESHOLDS",
                    classifierVersion = CLASSIFIER_VERSION,
                    computedAtEpochSecond = start,
                ),
            ),
        )
        val steps = StepRepository(database, FakeStepSource(), SettingsRepository(preferences("settings")), clock, scope)

        val today = steps.observeDailyBreakdowns(listOf(LocalDate.parse("2026-03-10"))).first().values.single()

        assertEquals(90L, today.totalSteps)
        assertEquals(0L, today.workoutSteps)
    }
}
