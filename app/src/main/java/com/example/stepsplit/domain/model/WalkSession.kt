package com.example.stepsplit.domain.model

import com.example.stepsplit.domain.aggregation.EpochInterval
import com.example.stepsplit.domain.classification.BoutClassification
import com.example.stepsplit.domain.classification.ClassificationReasonCode
import com.example.stepsplit.domain.classification.ClassifiedBout

enum class SessionOrigin { AUTO, MANUAL }

/**
 * A walking session as shown to the user: either an automatically detected bout (optionally
 * overridden by the user) or an explicitly recorded manual walk. [anchorEpochSecond] is the
 * stable key used to attach/find a manual override for AUTO-origin sessions; it is null for
 * manual walks, which are not reclassifiable.
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
 * Combines the automatically-derived bout classification with any manual override, and folds in
 * fully-manual "Start walk / Finish walk" sessions. Manual classifications always take precedence
 * over automatic ones, and raw bouts are never discarded even when overridden.
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

    fun manualWalkSession(
        id: Long,
        startEpochSecond: Long,
        endEpochSecond: Long,
        steps: Long,
    ): WalkSession {
        val elapsedMinutes = ((endEpochSecond - startEpochSecond) / 60).coerceAtLeast(1)
        return WalkSession(
            id = "manual:$id",
            startEpochSecond = startEpochSecond,
            endEpochSecond = endEpochSecond,
            steps = steps,
            activeMinutes = elapsedMinutes.toInt(),
            cadence = steps.toDouble() / elapsedMinutes,
            classification = BoutClassification.WORKOUT,
            origin = SessionOrigin.MANUAL,
            confidence = 1.0,
            reasonCode = ClassificationReasonCode.MANUALLY_RECORDED,
            isReclassifiable = false,
            anchorEpochSecond = null,
        )
    }

    fun workoutIntervals(sessions: List<WalkSession>): List<EpochInterval> = sessions
        .filter { it.classification == BoutClassification.WORKOUT }
        .map { EpochInterval(it.startEpochSecond, it.endEpochSecond) }
}
