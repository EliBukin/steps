package com.example.stepsplit.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stepsplit.data.repository.StepRepository
import kotlinx.coroutines.CancellationException

/**
 * The durable half of pending-bucket finalization (see [PendingBucketFinalizationScheduler]'s own
 * doc comment for why an in-process timer alone is insufficient - it does not survive process
 * death). Simply calls [StepRepository.finalizeDuePendingBuckets]; a due bucket resolves to
 * `ACCEPTED_*` or `REJECTED_UNVERIFIED` per whatever evidence is already in Room by the time this
 * runs - this worker makes no acceptance decision of its own.
 */
class PendingBucketFinalizationWorker(
    context: Context,
    params: WorkerParameters,
    private val stepRepository: StepRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        stepRepository.finalizeDuePendingBuckets()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }
}
