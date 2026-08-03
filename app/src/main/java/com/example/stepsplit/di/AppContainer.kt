package com.example.stepsplit.di

import android.content.Context
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.LocalRecordingStepSource
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.trip.FusedTripLocationClient
import com.example.stepsplit.data.trip.TripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRepository
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

    // ---- Trip Route Recording (see data.trip.TripRepository) - entirely separate from the step
    // sync pipeline above; nothing here is read or written by StepRepository, and vice versa. ----

    val tripRepository: TripRepository = TripRepository(
        database = database,
        clock = clock,
    )

    val locationClient: TripLocationClient = FusedTripLocationClient(context)

    // Owns TripRecordingCoordinator's location-collecting coroutine - a plain SupervisorJob-backed
    // scope living for the process lifetime, the same pattern as repositoryScope above. Recording
    // itself only ever runs while TripRecordingService is alive (see that class), but the
    // coordinator instance itself is process-lifetime like every other dependency here so a
    // service restart after process death reuses it rather than needing its own construction path.
    private val tripRecordingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tripRecordingCoordinator: TripRecordingCoordinator = TripRecordingCoordinator(
        repository = tripRepository,
        locationClient = locationClient,
        scope = tripRecordingScope,
    )
}
