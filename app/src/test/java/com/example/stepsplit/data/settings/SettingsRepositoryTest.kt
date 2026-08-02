package com.example.stepsplit.data.settings

import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepGoals
import com.example.stepsplit.domain.model.SyncFailure
import com.example.stepsplit.domain.model.SyncFailureCategory
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repository = SettingsRepository(context)
    }

    @After
    fun tearDown() = runTest {
        // See the class-level note on other test files: Preferences DataStore persists to a real
        // file, not guaranteed fresh per test method under Robolectric.
        repository.resetThresholds()
        repository.clearSyncFailure()
    }

    @Test
    fun `default daily goal is 15000 steps`() = runTest {
        assertEquals(StepGoals.DEFAULT_DAILY_GOAL, repository.settings.first().goals.dailyGoalSteps)
    }

    @Test
    fun `setting a valid daily goal is persisted and readable`() = runTest {
        assertTrue(repository.setDailyGoal(20_000))
        assertEquals(20_000L, repository.settings.first().goals.dailyGoalSteps)
    }

    @Test
    fun `a goal of zero is rejected and the previous value is kept`() = runTest {
        repository.setDailyGoal(12_000)

        val accepted = repository.setDailyGoal(0)

        assertFalse(accepted)
        assertEquals(12_000L, repository.settings.first().goals.dailyGoalSteps)
    }

    @Test
    fun `a negative goal is rejected`() = runTest {
        assertFalse(repository.setDailyGoal(-500))
    }

    @Test
    fun `resetting thresholds restores every default value`() = runTest {
        repository.setThresholds(ClassificationThresholds(minSteps = 1234, minCadenceStepsPerMinute = 42.0))
        repository.resetThresholds()

        assertEquals(ClassificationThresholds.DEFAULT, repository.settings.first().thresholds)
    }

    @Test
    fun `invalid thresholds are rejected`() = runTest {
        val accepted = repository.setThresholds(ClassificationThresholds(maxGapMinutes = 5, idleFinalizeMinutes = 2))
        assertFalse(accepted)
        assertEquals(ClassificationThresholds.DEFAULT, repository.settings.first().thresholds)
    }

    @Test
    fun `no sync failure is recorded by default`() = runTest {
        assertNull(repository.settings.first().lastSyncFailure)
    }

    @Test
    fun `a recorded sync failure is persisted with its category and time`() = runTest {
        repository.recordSyncFailure(SyncFailure(SyncFailureCategory.READ_FAILED, 1_000L))

        val failure = repository.settings.first().lastSyncFailure
        assertEquals(SyncFailureCategory.READ_FAILED, failure?.category)
        assertEquals(1_000L, failure?.atEpochSecond)
    }

    @Test
    fun `clearing a sync failure removes it`() = runTest {
        repository.recordSyncFailure(SyncFailure(SyncFailureCategory.SUBSCRIPTION_FAILED, 500L))

        repository.clearSyncFailure()

        assertNull(repository.settings.first().lastSyncFailure)
    }

    @Test
    fun `a recorded sync failure survives recreating the repository against the same storage`() = runTest {
        repository.recordSyncFailure(SyncFailure(SyncFailureCategory.UNKNOWN, 777L))

        // A fresh instance over the same underlying DataStore file - standing in for the process
        // (and every in-memory ViewModel/repository) being torn down and recreated, the way it
        // would be after the app is closed and reopened.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reopened = SettingsRepository(context)

        val failure = reopened.settings.first().lastSyncFailure
        assertEquals(SyncFailureCategory.UNKNOWN, failure?.category)
        assertEquals(777L, failure?.atEpochSecond)
    }

    @Test
    fun `recording a successful sync sets the timestamp and clears a previous failure together`() = runTest {
        repository.recordSyncFailure(SyncFailure(SyncFailureCategory.READ_FAILED, 100L))

        repository.recordSuccessfulSync(Instant.ofEpochSecond(200L))

        val settings = repository.settings.first()
        assertEquals(Instant.ofEpochSecond(200L), settings.lastSuccessfulSync)
        assertNull(settings.lastSyncFailure)
    }

    /**
     * [SettingsRepository.recordSuccessfulSync] must update the timestamp and remove the failure
     * keys in one DataStore transaction - never as two separate edits an observer could catch
     * between. Proven by collecting every emission of [SettingsRepository.settings] in the
     * background while the successful sync is recorded, then checking that not one of them
     * combines the new timestamp with the old (pre-success) failure.
     */
    @Test
    fun `an observer never sees an emission combining the new success timestamp with the old failure`() = runTest {
        // Preferences DataStore is a real file not guaranteed fresh per test method (see the
        // class-level note) - pin a known baseline timestamp first so a leftover value from a
        // sibling test can never coincidentally match the target instant used below.
        repository.recordSuccessfulSync(Instant.EPOCH)
        repository.recordSyncFailure(SyncFailure(SyncFailureCategory.READ_FAILED, 100L))

        val observed = mutableListOf<AppSettings>()
        val collector = launch { repository.settings.collect { observed.add(it) } }
        awaitCondition { observed.isNotEmpty() }

        repository.recordSuccessfulSync(Instant.ofEpochSecond(200L))
        awaitCondition { observed.any { it.lastSuccessfulSync == Instant.ofEpochSecond(200L) } }
        collector.cancel()

        val badIntermediateState = observed.any {
            it.lastSuccessfulSync == Instant.ofEpochSecond(200L) && it.lastSyncFailure != null
        }
        assertFalse("no emission may combine the new success timestamp with the stale failure", badIntermediateState)
    }

    /** Polls (real, but tiny and bounded) since DataStore's own dispatcher is a real background thread independent of this test's virtual coroutine time. */
    private suspend fun TestScope.awaitCondition(maxAttempts: Int = 200, condition: () -> Boolean) {
        var attempts = 0
        while (!condition() && attempts < maxAttempts) {
            runCurrent()
            Thread.sleep(1)
            attempts++
        }
        assertTrue("condition was not met in time", condition())
    }
}
