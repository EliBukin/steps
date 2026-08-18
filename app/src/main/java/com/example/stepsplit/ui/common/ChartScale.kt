package com.example.stepsplit.ui.common

/**
 * Pure scale-axis math for [WeeklyStackedBarChart]. No Android/Compose dependency, so the boundary
 * cases (an all-zero week, a single huge outlier day) are plain-JVM unit-testable without
 * Robolectric.
 */
internal object ChartScale {

    /**
     * The axis ceiling: a "nice" round number (1, 2, or 5 times a power of ten) at or above the
     * largest [values] entry and at or above [goalSteps], so the goal reference line is never
     * clipped off the top of the chart. Always at least 1, so an all-zero week still renders a
     * sensible axis instead of dividing by zero.
     */
    fun computeMax(values: List<Long>, goalSteps: Long): Long {
        val largest = (values.maxOrNull() ?: 0L).coerceAtLeast(goalSteps).coerceAtLeast(1L)
        return niceCeiling(largest)
    }

    /**
     * Rounds [value] up to a "nice" 1x/2x/5x/10x-of-a-power-of-ten ceiling at or above it, without
     * ever overflowing `Long` arithmetic - an extreme [value] (up to `Long.MAX_VALUE`) still
     * terminates in a bounded number of steps and returns a finite, non-negative result instead of
     * silently wrapping into a bogus negative axis ceiling.
     */
    private fun niceCeiling(value: Long): Long {
        if (value <= 0L) return 1L
        var magnitude = 1L
        while (magnitude <= Long.MAX_VALUE / 10 && magnitude * 10 <= value) {
            magnitude *= 10
        }
        for (multiplier in longArrayOf(1L, 2L, 5L, 10L)) {
            val candidate = if (magnitude > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else magnitude * multiplier
            if (candidate >= value) return candidate
        }
        return Long.MAX_VALUE
    }

    /** The stacked-bar segment heights for one day, as fractions (0f..1f) of the plotting rectangle. */
    data class SegmentFractions(val workout: Float, val incidental: Float, val remainder: Float)

    /**
     * Splits one day's bar into workout/incidental/empty-remainder fractions against [scaleMax].
     * Each fraction is independently clamped to `[0, 1]` - a defensive floor/ceiling, not something
     * that should occur in practice since [scaleMax] is always derived from the same totals - so a
     * mixed day (both segments nonzero) or a day at or above the axis ceiling never produces a
     * negative or over-full segment.
     */
    fun computeSegmentFractions(workoutSteps: Long, incidentalSteps: Long, scaleMax: Long): SegmentFractions {
        val workout = (workoutSteps.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f)
        val incidental = (incidentalSteps.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f)
        val remainder = (1f - workout - incidental).coerceAtLeast(0f)
        return SegmentFractions(workout = workout, incidental = incidental, remainder = remainder)
    }

    /**
     * The whole bar's normalized height, as a fraction (0f..1f) of the plotting rectangle: exactly
     * [totalSteps] divided by [scaleMax]. Deliberately independent of [computeSegmentFractions] so
     * the bar's own geometry is derived directly from the total the chart is actually displaying,
     * rather than depending on the `totalSteps == workoutSteps + incidentalSteps` invariant that
     * lives in a different subsystem (classification/aggregation) still holding.
     */
    fun computeTotalFraction(totalSteps: Long, scaleMax: Long): Float =
        (totalSteps.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f)
}
