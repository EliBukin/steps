package com.example.stepsplit.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Schedules the unique periodic sync work. [ExistingPeriodicWorkPolicy.KEEP] makes this safe to
 * call on every app start: if the periodic job is already scheduled, WorkManager leaves it alone
 * instead of resetting its schedule or creating a duplicate.
 */
object SyncScheduler {
    const val UNIQUE_WORK_NAME = "step_sync_periodic"
    private val SYNC_INTERVAL: Duration = Duration.ofHours(6)

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<StepSyncWorker>(SYNC_INTERVAL)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
