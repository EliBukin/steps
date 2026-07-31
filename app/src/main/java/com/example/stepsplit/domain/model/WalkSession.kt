package com.example.stepsplit.domain.model

import com.example.stepsplit.domain.aggregation.EpochInterval
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.ClassificationReasonCode
import com.example.stepsplit.domain.classification.ClassifiedBout

/**
 * [AUTO] is an automatically detected bout shown with its raw classifier result; [MANUAL] is an
 * automatically detected bout whose classification was overridden by the user on the Sessions
 * screen. There is no session type recorded independently of automatic detection - see
 * [SessionMerger].
 */
enum class SessionOrigin { AUTO, MANUAL }

/**
 * A walking session as shown to the user: an automatically detected bout, optionally reclassified
 * by the user. [anchorEpochSecond] is the stable key used to attach/find a manual override.
 */
data class WalkSession(
    val id: String,
    val startEpochSecond: Long,
    val endEpochSecond: Long,
    val steps: Long,
    val activeMinutes: Int,
    val cadence: Double,
    val classification: BoutClassification,
    val origin: SessionOrigin,
    val confidence: Double,
    val reasonCode: ClassificationReasonCode,
    val isReclassifiable: Boolean,
    val anchorEpochSecond: Long?,
)

/**
 * Turns automatically classified bouts into the sessions shown to the user, applying any manual
 * reclassification on top. Manual classifications always take precedence over automatic ones, and
 * raw bouts are never discarded even when overridden - see [com.example.stepsplit.data.repository.StepRepository.reclassify].
 */
object SessionMerger {

    fun fromAutoBouts(
        autoBouts: List<ClassifiedBout>,
        overrides: Map<Long, BoutClassification>,
    ): List<WalkSession> = autoBouts.map { bout ->
        val override = overrides[bout.startEpochSecond]
        WalkSession(
            id = "auto:${bout.startEpochSecond}",
            startEpochSecond = bout.startEpochSecond,
            endEpochSecond = bout.endEpochSecond,
            steps = bout.steps,
            activeMinutes = bout.activeMinutes,
            cadence = bout.cadence,
            classification = override ?: bout.classification,
            origin = if (override != null) SessionOrigin.MANUAL else SessionOrigin.AUTO,
            confidence = if (override != null) 1.0 else bout.confidence,
            reasonCode = if (override != null) ClassificationReasonCode.MANUALLY_RECLASSIFIED else bout.reasonCode,
            isReclassifiable = true,
            anchorEpochSecond = bout.startEpochSecond,
        )
    }

    fun workoutIntervals(sessions: List<WalkSession>): List<EpochInterval> = sessions
        .filter { it.classification == BoutClassification.WORKOUT }
        .map { EpochInterval(it.startEpochSecond, it.endEpochSecond) }
}
