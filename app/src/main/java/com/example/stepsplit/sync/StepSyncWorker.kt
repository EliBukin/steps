package com.example.stepsplit.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stepsplit.data.motion.MotionEvidenceRegistrar
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.repository.SyncResult
import kotlinx.coroutines.CancellationException

/**
 * Reads the latest step data roughly every six hours via a unique periodic
 * [androidx.work.WorkRequest]. A missing permission or unavailable API is not a transient
 * failure - retrying will not fix it - so those cases report success (the worker simply does
 * nothing useful until the user grants permission, and will check again next run). Only genuine
 * transient failures (e.g. an unexpected exception mid-sync) request a retry, which WorkManager
 * backs off exponentially per [SyncScheduler.periodicRequest].
 *
 * Also doubles as the periodic backstop for two other concerns, both cheap and idempotent: (1)
 * re-registering Activity Recognition transitions/sampling ([MotionEvidenceRegistrar.ensureRegistered])
 * in case a prior registration attempt failed and nothing else has retried it since, and (2)
 * compacting the raw `motion_evidence` log ([StepRepository.compactMotionEvidence]) so it never
 * grows unbounded from continuous 15-second sampling.
 */
class StepSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val stepRepository: StepRepository,
    private val motionEvidenceRegistrar: MotionEvidenceRegistrar,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val result = stepRepository.syncNow()
        stepRepository.finalizeDuePendingBuckets()
        motionEvidenceRegistrar.ensureRegistered()
        stepRepository.compactMotionEvidence()
        when (result) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Unavailable -> Result.success()
            is SyncResult.Failed -> Result.retry()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }
}
