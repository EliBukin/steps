package com.example.stepsplit.di

import android.content.Context
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.LocalRecordingStepSource
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.sync.StepSyncWorkerFactory
import java.time.Clock

/**
 * Hand-written dependency container - the app is a single module with a handful of
 * long-lived dependencies, so a DI framework would add machinery without solving a real problem
 * here. Every dependency is constructed once, at application start, and lives for the process
 * lifetime.
 */
class AppContainer(context: Context) {
    val clock: Clock = Clock.systemDefaultZone()

    val database: StepSplitDatabase = StepSplitDatabase.build(context)

    val settingsRepository: SettingsRepository = SettingsRepository(context)

    val stepSource: StepSource = LocalRecordingStepSource(context)

    val stepRepository: StepRepository = StepRepository(
        database = database,
        stepSource = stepSource,
        settingsRepository = settingsRepository,
        clock = clock,
    )

    val workerFactory: StepSyncWorkerFactory = StepSyncWorkerFactory(stepRepository)
}
