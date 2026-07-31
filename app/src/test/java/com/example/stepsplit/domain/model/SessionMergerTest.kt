package com.example.stepsplit.domain.model

import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.ClassificationReasonCode
import com.example.stepsplit.domain.classification.ClassifiedBout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMergerTest {

    private val autoWorkout = ClassifiedBout(
        startEpochSecond = 1_000L,
        endEpochSecond = 1_000L + 20 * 60,
        steps = 1600,
        activeMinutes = 20,
        elapsedMinutes = 20,
        cadence = 80.0,
        classification = BoutClassification.WORKOUT,
        confidence = 0.9,
        reasonCode = ClassificationReasonCode.MEETS_ALL_THRESHOLDS,
    )

    @Test
    fun `a bout with no override keeps the automatic classification`() {
        val sessions = SessionMerger.fromAutoBouts(listOf(autoWorkout), overrides = emptyMap())
        val session = sessions.single()

        assertEquals(BoutClassification.WORKOUT, session.classification)
        assertEquals(SessionOrigin.AUTO, session.origin)
        assertTrue(session.isReclassifiable)
    }

    @Test
    fun `a manual override always wins over the automatic classification`() {
        val overrides = mapOf(autoWorkout.startEpochSecond to BoutClassification.INCIDENTAL)
        val session = SessionMerger.fromAutoBouts(listOf(autoWorkout), overrides).single()

        assertEquals(BoutClassification.INCIDENTAL, session.classification)
        assertEquals(SessionOrigin.MANUAL, session.origin)
        assertEquals(ClassificationReasonCode.MANUALLY_RECLASSIFIED, session.reasonCode)
    }

    @Test
    fun `overriding one bout does not affect a different bout`() {
        val otherBout = autoWorkout.copy(startEpochSecond = 50_000L, endEpochSecond = 50_000L + 1200)
        val overrides = mapOf(autoWorkout.startEpochSecond to BoutClassification.INCIDENTAL)

        val sessions = SessionMerger.fromAutoBouts(listOf(autoWorkout, otherBout), overrides)

        assertEquals(BoutClassification.INCIDENTAL, sessions.first { it.startEpochSecond == autoWorkout.startEpochSecond }.classification)
        assertEquals(BoutClassification.WORKOUT, sessions.first { it.startEpochSecond == otherBout.startEpochSecond }.classification)
    }

    @Test
    fun `workout intervals only include sessions currently classified as workout`() {
        val incidental = autoWorkout.copy(
            startEpochSecond = 50_000L,
            endEpochSecond = 50_000L + 600,
            classification = BoutClassification.INCIDENTAL,
        )
        val sessions = SessionMerger.fromAutoBouts(listOf(autoWorkout, incidental), overrides = emptyMap())

        val intervals = SessionMerger.workoutIntervals(sessions)

        assertEquals(1, intervals.size)
        assertEquals(autoWorkout.startEpochSecond, intervals.single().startEpochSecond)
    }
}
