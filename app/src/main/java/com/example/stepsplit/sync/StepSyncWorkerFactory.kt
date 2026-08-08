package com.example.stepsplit.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.stepsplit.data.motion.MotionEvidenceRegistrar
import com.example.stepsplit.data.repository.StepRepository

/** Constructs every `CoroutineWorker` this app schedules with its dependencies injected manually - no reflection. WorkManager accepts exactly one [WorkerFactory] per process, so every worker class lives here, not in a separate factory each. */
class StepSyncWorkerFactory(
    private val stepRepository: StepRepository,
    private val motionEvidenceRegistrar: MotionEvidenceRegistrar,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        StepSyncWorker::class.java.name -> StepSyncWorker(appContext, workerParameters, stepRepository, motionEvidenceRegistrar)
        PendingBucketFinalizationWorker::class.java.name -> PendingBucketFinalizationWorker(appContext, workerParameters, stepRepository)
        else -> null
    }
}
