package com.example.stepsplit.data.settings

import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepGoals
import com.example.stepsplit.domain.model.SyncFailure
import com.example.stepsplit.domain.model.SyncFailureCategory
import kotlinx.coroutines.flow.first
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
}
