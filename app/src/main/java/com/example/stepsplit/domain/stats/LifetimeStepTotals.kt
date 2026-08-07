package com.example.stepsplit.domain.stats

import java.time.LocalDate

/**
 * Raw lifetime totals for one step source, derived directly from every [source]-tagged row ever
 * stored in Room's `step_buckets` table - no date bound, no retention window, no dependency on
 * any UI-visible date range. See
 * [com.example.stepsplit.data.repository.StepRepository.observeLifetimeStats] and
 * [com.example.stepsplit.data.local.bucket.StepBucketDao.observeLifetimeAggregate] for why this is
 * safe to compute as a plain aggregate query rather than a separately maintained counter.
 */
data class LifetimeStepTotals(
    val lifetimeSteps: Long,
    val activeDays: Int,
    val bestDayDate: LocalDate?,
    val bestDaySteps: Long,
) {
    companion object {
        val EMPTY = LifetimeStepTotals(lifetimeSteps = 0L, activeDays = 0, bestDayDate = null, bestDaySteps = 0L)
    }
}
