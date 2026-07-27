package com.example.stepsplit.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalProgressTest {

    @Test
    fun `progress above the goal is never capped`() {
        val progress = GoalProgress(achievedSteps = 18_000, goalSteps = 15_000)
        assertEquals(120.0, progress.percent, 0.0001)
    }

    @Test
    fun `the visual indicator clamps to 100 percent while the number does not`() {
        val progress = GoalProgress(achievedSteps = 30_000, goalSteps = 15_000)
        assertEquals(200.0, progress.percent, 0.0001)
        assertEquals(1f, progress.clampedFraction, 0.0001f)
    }

    @Test
    fun `weekly goal is always seven times the daily goal`() {
        val goals = StepGoals(dailyGoalSteps = 15_000)
        assertEquals(105_000L, goals.weeklyGoalSteps)
    }

    @Test
    fun `changing the daily goal changes the derived weekly goal`() {
        val goals = StepGoals(dailyGoalSteps = 10_000)
        assertEquals(70_000L, goals.weeklyGoalSteps)
    }

    @Test
    fun `a goal of zero is invalid and progress reports zero percent instead of dividing by zero`() {
        assertFalse(StepGoals.isValidDailyGoal(0))
        val progress = GoalProgress(achievedSteps = 5_000, goalSteps = 0)
        assertEquals(0.0, progress.percent, 0.0001)
        assertFalse(progress.isGoalValid)
    }

    @Test
    fun `a negative goal is invalid`() {
        assertFalse(StepGoals.isValidDailyGoal(-100))
    }

    @Test
    fun `a reasonable positive goal is valid`() {
        assertTrue(StepGoals.isValidDailyGoal(15_000))
    }
}
