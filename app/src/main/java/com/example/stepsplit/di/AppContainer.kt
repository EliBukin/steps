package com.example.stepsplit.di

import android.content.Context
import android.util.Log
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.motion.ActivityRecognitionGateway
import com.example.stepsplit.data.motion.AsyncMotionDiagnosticsHealthRecorder
import com.example.stepsplit.data.motion.MotionDiagnosticsHealthSink
import com.example.stepsplit.data.motion.MotionDiagnosticsStore
import com.example.stepsplit.data.motion.MotionEvidenceConverter
import com.example.stepsplit.data.motion.MotionEvidenceRegistrar
import com.example.stepsplit.data.motion.PlayServicesActivityRecognitionGateway
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.AsyncStepSourceHealthRecorder
import com.example.stepsplit.data.stepsource.LocalRecordingStepSource
import com.example.stepsplit.data.stepsource.StepSource
import com.example.stepsplit.data.stepsource.StepSourceHealthSink
import com.example.stepsplit.data.stepsource.StepSourceHealthStore
import com.example.stepsplit.data.trip.FusedTripLocationClient
import com.example.stepsplit.data.trip.TripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.debug.DeviceDiagnostics
import com.example.stepsplit.debug.DeviceDiagnosticsSnapshot
import com.example.stepsplit.domain.time.DeviceZoneClock
import com.example.stepsplit.domain.validation.ValidationConstants
import com.example.stepsplit.sync.PendingBucketFinalizationScheduler
import com.example.stepsplit.sync.StepSyncWorkerFactory
import com.example.stepsplit.sync.WorkManagerPendingBucketFinalizationScheduler
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

    // Diagnostics for production acquisition health (subscribe/read outcomes, whether any sample
    // has ever been observed) - a concern separate from both StepSourceAvailability and
    // SyncFailure, see StepSourceHealthStore's own doc comment. Held here (not just inside
    // LocalRecordingStepSource) so the UI layer can observe it directly for the "waiting for first
    // step data" state and the debug diagnostics panel.
    val stepSourceHealthStore: StepSourceHealthStore = StepSourceHealthStore(context)

    // Owns AsyncStepSourceHealthRecorder's single background consumer coroutine - process-lifetime,
    // the same pattern as repositoryScope/tripRecordingScope below. Kept separate from those two:
    // this scope's only job is draining diagnostic events, so a problem in either of the other
    // subsystems can never starve or interfere with it, and vice versa.
    private val healthEventScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // LocalRecordingStepSource must never await real diagnostic persistence directly on its own
    // acquisition coroutine (a wedged DataStore write would then block gateway.subscribe()/readData()
    // themselves) - see AsyncStepSourceHealthRecorder's own doc comment. stepSourceHealthStore
    // above is still exposed directly (not through this wrapper) for the UI layer's own
    // snapshot/clear() access, which has no such latency constraint.
    private val asyncHealthRecorder: StepSourceHealthSink = AsyncStepSourceHealthRecorder(stepSourceHealthStore, healthEventScope)

    val stepSource: StepSource = LocalRecordingStepSource(context, healthStore = asyncHealthRecorder, clock = clock)

    /**
     * Debug-only device/Play-services snapshot for the Settings debug panel - null in release
     * builds (never collected there at all) and null if collection itself fails for any reason
     * (PackageManager/SensorManager misbehaving is not this app's problem to crash over). Lazily
     * computed on first access rather than eagerly here in the constructor: [AppContainer] is built
     * synchronously on the main thread from [com.example.stepsplit.StepSplitApplication.onCreate],
     * so anything computed unconditionally at this point sits directly on the app's cold-start path.
     * Nothing touches this property until a debug build's Settings screen is actually opened
     * (see `SettingsViewModel`), so app startup itself never pays for it at all.
     */
    val deviceDiagnostics: DeviceDiagnosticsSnapshot? by lazy {
        if (!BuildConfig.DEBUG) return@lazy null
        try {
            DeviceDiagnostics.collect(context)
        } catch (e: Exception) {
            Log.d("AppContainer", "device diagnostics collection failed (ignored): ${e.message}")
            null
        }
    }

    val validationConstants: ValidationConstants = ValidationConstants.DEFAULT

    // ---- Strict vehicle-aware step validation - motion evidence acquisition ----

    /** Owns [MotionEvidenceReceiver]/[BootAndUpdateReceiver]'s ingestion work - process-lifetime, the same pattern as repositoryScope/tripRecordingScope below, kept separate so a problem in ingestion can never starve or be starved by the others. */
    val motionEvidenceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val motionEvidenceConverter: MotionEvidenceConverter = MotionEvidenceConverter(context, clock)

    /** Read-side diagnostics (UI/Settings panel) - see [MotionDiagnosticsStore]'s own doc comment. */
    val motionDiagnosticsStore: MotionDiagnosticsStore = MotionDiagnosticsStore(context)

    private val motionDiagnosticsEventScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Write-side, non-suspending sink - see AsyncMotionDiagnosticsHealthRecorder's own doc comment
    // for why acquisition/ingestion must never depend on the suspending MotionDiagnosticsStore directly.
    private val asyncMotionDiagnosticsRecorder: MotionDiagnosticsHealthSink =
        AsyncMotionDiagnosticsHealthRecorder(motionDiagnosticsStore, motionDiagnosticsEventScope)

    val activityRecognitionGateway: ActivityRecognitionGateway = PlayServicesActivityRecognitionGateway(context)

    val motionEvidenceRegistrar: MotionEvidenceRegistrar = MotionEvidenceRegistrar(
        context = context,
        gateway = activityRecognitionGateway,
        healthStore = asyncMotionDiagnosticsRecorder,
        clock = clock,
    )

    // Owns StepRepository's one-shot trailing-bout finalization timer (see
    // StepRepository.rescheduleFinalizationJob) - a plain SupervisorJob-backed scope, not a
    // Service, that simply lives for the process lifetime like every other dependency here.
    private val repositoryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pendingBucketFinalizationScheduler: PendingBucketFinalizationScheduler = WorkManagerPendingBucketFinalizationScheduler(
        context = context,
        database = database,
        sourceId = stepSource.id,
        pendingFinalizationDelaySeconds = validationConstants.pendingFinalizationDelaySeconds,
    )

    val stepRepository: StepRepository = StepRepository(
        database = database,
        stepSource = stepSource,
        settingsRepository = settingsRepository,
        clock = clock,
        repositoryScope = repositoryScope,
        motionDiagnosticsHealthSink = asyncMotionDiagnosticsRecorder,
        pendingBucketFinalizationScheduler = pendingBucketFinalizationScheduler,
        validationConstants = validationConstants,
    )

    val workerFactory: StepSyncWorkerFactory = StepSyncWorkerFactory(stepRepository, motionEvidenceRegistrar)

    // ---- Trip Route Recording (see data.trip.TripRepository) - entirely separate from the step
    // sync/validation pipeline above; nothing here is read or written by StepRepository, and vice
    // versa. GPS trip data is never mixed into step distance/validation, per the product requirement
    // that these two datasets stay logically separate. ----

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
    // Not private: TripRecordingService also uses it as TripRecordingCommandController's
    // reconciliationScope (see that class's shutdown() doc comment) - the fire-and-forget trip
    // reconciliation onDestroy triggers must outlive serviceScope, which onDestroy cancels.
    val tripRecordingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val tripRecordingCoordinator: TripRecordingCoordinator = TripRecordingCoordinator(
        repository = tripRepository,
        locationClient = locationClient,
        scope = tripRecordingScope,
    )
}
