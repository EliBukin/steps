package com.example.stepsplit.domain.classification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificationThresholdsTest {

    @Test
    fun `default thresholds are valid`() {
        assertTrue(ClassificationThresholds.DEFAULT.isValid())
    }

    @Test
    fun `zero or negative values are invalid`() {
        assertFalse(ClassificationThresholds(minSteps = 0).isValid())
        assertFalse(ClassificationThresholds(minActiveMinutes = 0).isValid())
        assertFalse(ClassificationThresholds(minCadenceStepsPerMinute = 0.0).isValid())
        assertFalse(ClassificationThresholds(maxGapMinutes = -1).isValid())
    }

    @Test
    fun `idle-finalize must exceed max-gap or every gap would both continue and end a bout`() {
        assertFalse(ClassificationThresholds(maxGapMinutes = 3, idleFinalizeMinutes = 3).isValid())
        assertFalse(ClassificationThresholds(maxGapMinutes = 4, idleFinalizeMinutes = 3).isValid())
        assertTrue(ClassificationThresholds(maxGapMinutes = 2, idleFinalizeMinutes = 3).isValid())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `classifying with invalid thresholds fails fast instead of producing nonsense results`() {
        WalkClassifier.classify(listOf(MinuteBucket(0, 10)), ClassificationThresholds(minSteps = 0), Long.MAX_VALUE)
    }
}
