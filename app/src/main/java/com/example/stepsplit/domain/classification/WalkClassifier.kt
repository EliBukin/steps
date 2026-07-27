package com.example.stepsplit.domain.classification

/**
 * Pure, deterministic bout-detection and workout classification. No Android dependencies, no
 * I/O, no wall-clock reads - safe to rerun over the full raw history at any time without losing
 * manual corrections, because manual overrides are stored and merged in a separate layer.
 *
 * Algorithm:
 * 1. Keep only minutes with steps > 0 ("active minutes"), sorted and de-duplicated.
 * 2. Group consecutive active minutes into a bout as long as the gap between them is at most
 *    [ClassificationThresholds.maxGapMinutes]. A gap of [ClassificationThresholds.idleFinalizeMinutes]
 *    or more finalizes the bout and starts a new one.
 * 3. Classify each finalized bout as a likely WORKOUT only if it satisfies every threshold
 *    (elapsed duration, active minutes, total steps, average cadence); otherwise INCIDENTAL.
 */
object WalkClassifier {

    private const val SECONDS_PER_MINUTE = 60L

    fun classify(
        buckets: List<MinuteBucket>,
        thresholds: ClassificationThresholds = ClassificationThresholds.DEFAULT,
    ): List<ClassifiedBout> {
        require(thresholds.isValid()) { "Invalid classification thresholds: $thresholds" }

        val activeMinutes = buckets
            .filter { it.steps > 0 }
            .groupingBy { it.startEpochSecond }
            .fold(0L) { acc, bucket -> acc + bucket.steps }
            .map { (start, steps) -> MinuteBucket(start, steps) }
            .sortedBy { it.startEpochSecond }

        if (activeMinutes.isEmpty()) return emptyList()

        val bouts = mutableListOf<MutableList<MinuteBucket>>()
        var current = mutableListOf(activeMinutes.first())
        for (i in 1 until activeMinutes.size) {
            val previous = activeMinutes[i - 1]
            val minute = activeMinutes[i]
            // Idle minutes strictly between the two active minutes (back-to-back minutes = 0 idle).
            val idleMinutes = (minute.startEpochSecond - previous.startEpochSecond) / SECONDS_PER_MINUTE - 1
            if (idleMinutes <= thresholds.maxGapMinutes) {
                current.add(minute)
            } else {
                bouts.add(current)
                current = mutableListOf(minute)
            }
        }
        bouts.add(current)

        return bouts.map { classifyBout(it, thresholds) }
    }

    private fun classifyBout(
        minutes: List<MinuteBucket>,
        thresholds: ClassificationThresholds,
    ): ClassifiedBout {
        val start = minutes.first().startEpochSecond
        val end = minutes.last().startEpochSecond + SECONDS_PER_MINUTE
        val steps = minutes.sumOf { it.steps }
        val activeMinuteCount = minutes.size
        val elapsedMinutes = ((end - start) / SECONDS_PER_MINUTE).toInt()
        val cadence = if (activeMinuteCount > 0) steps.toDouble() / activeMinuteCount else 0.0

        val passesDuration = elapsedMinutes >= thresholds.minBoutDurationMinutes
        val passesActive = activeMinuteCount >= thresholds.minActiveMinutes
        val passesSteps = steps >= thresholds.minSteps
        val passesCadence = cadence >= thresholds.minCadenceStepsPerMinute
        val passed = passesDuration && passesActive && passesSteps && passesCadence

        val reasonCode = reasonCodeFor(passed, passesDuration, passesActive, passesSteps, passesCadence)
        val confidence = confidenceFor(passed, elapsedMinutes, activeMinuteCount, steps, cadence, thresholds)

        return ClassifiedBout(
            startEpochSecond = start,
            endEpochSecond = end,
            steps = steps,
            activeMinutes = activeMinuteCount,
            elapsedMinutes = elapsedMinutes,
            cadence = cadence,
            classification = if (passed) BoutClassification.WORKOUT else BoutClassification.INCIDENTAL,
            confidence = confidence,
            reasonCode = reasonCode,
        )
    }

    private fun reasonCodeFor(
        passed: Boolean,
        passesDuration: Boolean,
        passesActive: Boolean,
        passesSteps: Boolean,
        passesCadence: Boolean,
    ): ClassificationReasonCode {
        if (passed) return ClassificationReasonCode.MEETS_ALL_THRESHOLDS
        val failures = listOf(passesDuration, passesActive, passesSteps, passesCadence).count { !it }
        if (failures > 1) return ClassificationReasonCode.MULTIPLE_THRESHOLDS_NOT_MET
        return when {
            !passesDuration -> ClassificationReasonCode.DURATION_TOO_SHORT
            !passesActive -> ClassificationReasonCode.TOO_FEW_ACTIVE_MINUTES
            !passesSteps -> ClassificationReasonCode.TOO_FEW_STEPS
            else -> ClassificationReasonCode.CADENCE_TOO_LOW
        }
    }

    /**
     * Transparent confidence heuristic: how far the bout's metrics sit from the pass/fail
     * boundary, averaged across the four dimensions. Exactly-at-threshold bouts get 0.5
     * (a coin flip), rising to 1.0 as metrics clear (or fall short of) the thresholds by 2x.
     */
    private fun confidenceFor(
        passed: Boolean,
        elapsedMinutes: Int,
        activeMinutes: Int,
        steps: Long,
        cadence: Double,
        thresholds: ClassificationThresholds,
    ): Double {
        val ratios = listOf(
            elapsedMinutes.toDouble() / thresholds.minBoutDurationMinutes,
            activeMinutes.toDouble() / thresholds.minActiveMinutes,
            steps.toDouble() / thresholds.minSteps,
            cadence / thresholds.minCadenceStepsPerMinute,
        )
        val avgRatio = ratios.average()
        val margin = if (passed) {
            (avgRatio - 1.0).coerceIn(0.0, 1.0)
        } else {
            (1.0 - avgRatio).coerceIn(0.0, 1.0)
        }
        return (0.5 + 0.5 * margin).coerceIn(0.0, 1.0)
    }
}
