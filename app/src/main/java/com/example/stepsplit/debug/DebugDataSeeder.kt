package com.example.stepsplit.debug

import com.example.stepsplit.BuildConfig
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.di.AppContainer

/**
 * Debug-only sample data facility: generates a week of synthetic incidental + workout step
 * data and imports it through the normal repository pipeline (normalize -> store -> classify),
 * so the UI can be exercised without a real device walking around. The [BuildConfig.DEBUG] guard
 * is defense in depth on top of the Settings screen only showing the trigger button in debug
 * builds - this facility is never reachable from release behavior.
 */
object DebugDataSeeder {
    private const val SAMPLE_DAYS = 7
    private const val SOURCE_ID = "debug_sample_data"

    suspend fun seed(container: AppContainer) {
        if (!BuildConfig.DEBUG) return

        val now = container.clock.instant()
        val fakeSource = FakeStepSource.withSampleData(days = SAMPLE_DAYS, endInstant = now)
        val intervals = fakeSource.readSteps(now.minusSeconds(SAMPLE_DAYS * 24L * 3600L), now)

        container.stepRepository.debugImportRawIntervals(SOURCE_ID, intervals)
    }
}
