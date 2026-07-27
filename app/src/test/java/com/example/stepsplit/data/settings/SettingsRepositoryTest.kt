package com.example.stepsplit.data.settings

import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.domain.classification.ClassificationThresholds
import com.example.stepsplit.domain.model.StepGoals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
