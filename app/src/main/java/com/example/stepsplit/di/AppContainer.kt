package com.example.stepsplit.di

import android.content.Context
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.LocalRecordingStepSource
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.domain.time.DeviceZoneClock
import com.example.stepsplit.sync.StepSyncWorkerFactory
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-written dependency container - the app is a single module with a handful of
 * long-lived dependencies, so a DI framework would add machinery without solving a real problem
 * here. Every dependency is constructed once, at application start, and lives for the process
 * lifetime.
 */
class AppContainer(context: Context) {
    // Not Clock.systemDefaultZone(): that freezes the zone at construction time, so a device
    // timezone change while the process stays alive would go unnoticed until restart.
    val clock: Clock = DeviceZoneClock()

    val database: StepSplitDatabase = StepSplitDatabase.build(context)

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    val stepSource: StepSource = LocalRecordingStepSource(context)

    // Owns StepRepository's one-shot trailing-bout finalization timer (see
    // StepRepository.rescheduleFinalizationJob) - a plain SupervisorJob-backed scope, not a
    // Service, that simply lives for the process lifetime like every other dependency here.
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val stepRepository: StepRepository = StepRepository(
        database = database,
        stepSource = stepSource,
        settingsRepository = settingsRepository,
        clock = clock,
        repositoryScope = repositoryScope,
    )

    val workerFactory: StepSyncWorkerFactory = StepSyncWorkerFactory(stepRepository)
}
