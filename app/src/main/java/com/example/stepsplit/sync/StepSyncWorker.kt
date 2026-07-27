package com.example.stepsplit.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
 */
class StepSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val stepRepository: StepRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        when (val result = stepRepository.syncNow()) {
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
