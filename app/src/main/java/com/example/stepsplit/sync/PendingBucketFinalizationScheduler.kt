package com.example.stepsplit.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.stepsplit.data.local.StepSplitDatabase
import java.util.concurrent.TimeUnit

/** What [com.example.stepsplit.data.repository.StepRepository] depends on to durably schedule the next pending-bucket finalization - kept as an interface so tests can use a trivial fake instead of real WorkManager. */
interface PendingBucketFinalizationScheduler {
    /**
     * Recomputes the TRUE current earliest `PENDING` deadline from the database and (re)schedules
     * exactly one `WorkManager` one-shot job for it. Always recomputing the global minimum - never
     * threading through the specific bucket that happened to trigger this call - is what makes
     * [ExistingWorkPolicy.REPLACE] safe here: a later-arriving bucket with a farther-out deadline
     * can never push back an already-scheduled sooner one, because the value scheduled is always
     * independently the true minimum, not whichever caller ran most recently.
     */
    suspend fun rescheduleForEarliestPendingDeadline()
}

/** Real, `WorkManager`-backed implementation. */
class WorkManagerPendingBucketFinalizationScheduler(
    private val context: Context,
    private val database: StepSplitDatabase,
    private val sourceId: String,
    private val pendingFinalizationDelaySeconds: Int,
) : PendingBucketFinalizationScheduler {

    override suspend fun rescheduleForEarliestPendingDeadline() {
        val earliestObservationEnd = database.stepBucketDao().earliestPendingObservationEnd(sourceId) ?: return
        val deadlineEpochMilli = (earliestObservationEnd + pendingFinalizationDelaySeconds) * 1000L
        val delayMillis = (deadlineEpochMilli - System.currentTimeMillis()).coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<PendingBucketFinalizationWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "pending_bucket_finalization"
    }
}
