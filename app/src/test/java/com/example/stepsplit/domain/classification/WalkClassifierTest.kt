package com.example.stepsplit.domain.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkClassifierTest {

    private val thresholds = ClassificationThresholds.DEFAULT

    private fun minutes(startEpochSecond: Long, count: Int, stepsPerMinute: Long): List<MinuteBucket> =
        (0 until count).map { MinuteBucket(startEpochSecond + it * 60L, stepsPerMinute) }

    @Test
    fun `empty input yields no bouts`() {
        assertTrue(WalkClassifier.classify(emptyList(), thresholds).isEmpty())
    }

    @Test
    fun `sustained brisk walk is classified as a workout`() {
        // 20 minutes, 80 steps per minute: elapsed=20, active=20, steps=1600, cadence=80 - all thresholds cleared.
        val bout = WalkClassifier.classify(minutes(0, 20, 80), thresholds).single()

        assertEquals(BoutClassification.WORKOUT, bout.classification)
        assertEquals(ClassificationReasonCode.MEETS_ALL_THRESHOLDS, bout.reasonCode)
        assertEquals(1600L, bout.steps)
        assertEquals(20, bout.activeMinutes)
        assertTrue("confidence should be high for a bout that comfortably clears every threshold", bout.confidence > 0.5)
    }

    @Test
    fun `short incidental burst around the house is not a workout`() {
        // 3 minutes only: fails duration, active-minutes, and steps simultaneously.
        val bout = WalkClassifier.classify(minutes(0, 3, 50), thresholds).single()

        assertEquals(BoutClassification.INCIDENTAL, bout.classification)
        assertEquals(ClassificationReasonCode.MULTIPLE_THRESHOLDS_NOT_MET, bout.reasonCode)
    }

    @Test
    fun `bout failing only the duration threshold reports that specific reason`() {
        // 8 consecutive active minutes, no internal gap: elapsed=8 (<10), active=8 (ok), steps=640 (ok), cadence=80 (ok).
        val bout = WalkClassifier.classify(minutes(0, 8, 80), thresholds).single()

        assertEquals(BoutClassification.INCIDENTAL, bout.classification)
        assertEquals(ClassificationReasonCode.DURATION_TOO_SHORT, bout.reasonCode)
    }

    @Test
    fun `gap of up to the max-gap threshold stays inside a single bout`() {
        // 5 active minutes, a 2-minute gap (still within maxGapMinutes), then 10 more active minutes.
        val firstPart = minutes(0, 5, 70)
        val secondPart = minutes(5 * 60L + 2 * 60L, 10, 70)
        val bouts = WalkClassifier.classify(firstPart + secondPart, thresholds)

        assertEquals(1, bouts.size)
        assertEquals(15, bouts.single().activeMinutes)
    }

    @Test
    fun `gap at the idle-finalize threshold splits into two bouts`() {
        // A 3-minute gap (== idleFinalizeMinutes) finalizes the first bout.
        val firstPart = minutes(0, 5, 70)
        val secondPart = minutes(5 * 60L + 3 * 60L, 10, 70)
        val bouts = WalkClassifier.classify(firstPart + secondPart, thresholds)

        assertEquals(2, bouts.size)
    }

    @Test
    fun `cadence exactly at the threshold passes`() {
        // 10 minutes at exactly 60 steps/min: cadence == minCadenceStepsPerMinute (inclusive boundary).
        val bout = WalkClassifier.classify(minutes(0, 10, 60), thresholds).single()

        assertEquals(BoutClassification.WORKOUT, bout.classification)
    }

    @Test
    fun `cadence just under the threshold fails only the cadence check`() {
        // 10 minutes of 599 total steps spread unevenly is awkward; instead use 10 minutes at 59 steps/min,
        // which also fails steps (590 < 600) - so bump minutes to keep steps high but cadence just under 60.
        val custom = ClassificationThresholds(minSteps = 500L)
        val bout = WalkClassifier.classify(minutes(0, 10, 59), custom).single()

        assertEquals(BoutClassification.INCIDENTAL, bout.classification)
        assertEquals(ClassificationReasonCode.CADENCE_TOO_LOW, bout.reasonCode)
    }

    @Test
    fun `a bout becomes a workout only once enough additional data arrives`() {
        // First classifier run over only the first 8 minutes: too short to be a workout.
        val partial = WalkClassifier.classify(minutes(0, 8, 80), thresholds).single()
        assertEquals(BoutClassification.INCIDENTAL, partial.classification)

        // A later sync brings in the rest of the same continuous walk (same start, more active minutes).
        val complete = WalkClassifier.classify(minutes(0, 20, 80), thresholds).single()
        assertEquals(BoutClassification.WORKOUT, complete.classification)
        assertEquals(partial.startEpochSecond, complete.startEpochSecond)
    }

    @Test
    fun `multiple minute readings for the same start second are summed before classifying`() {
        val duplicated = listOf(MinuteBucket(0, 40), MinuteBucket(0, 40)) + minutes(60, 9, 80)
        val bout = WalkClassifier.classify(duplicated, thresholds).single()

        assertEquals(80L + 9 * 80L, bout.steps)
    }
}
