package com.example.stepsplit.sync

import android.content.Context
import androidx.work.WorkManager

/**
 * Step import is foreground-only (see [com.example.stepsplit.ui.today.TodayViewModel.refresh] and
 * [com.example.stepsplit.ui.today.TodayScreen]'s resume effect): the app only requests Health
 * Connect's `READ_STEPS` permission, not `READ_HEALTH_DATA_IN_BACKGROUND`, and a WorkManager job
 * running while the app is backgrounded has no background-read permission to read with. There is
 * deliberately no periodic background worker here - Health Connect's own platform-level pedometer
 * keeps collecting regardless of whether this app is running; this app only needs to pull that
 * data in while it is actually in the foreground.
 *
 * An earlier version of this app DID schedule a unique periodic `step_sync_periodic` WorkManager
 * job every six hours. That job is durably persisted by WorkManager in its own database and
 * survives an in-place app update on its own - simply deleting the code that used to schedule it
 * does NOT cancel an already-persisted job on a device upgrading from that version. [cleanUp]
 * exists purely to undo that: it is safe and idempotent to call on every app start (a no-op once
 * the job has already been cancelled once).
 */
object SyncScheduler {
    /** Must exactly match the unique work name the old periodic job was scheduled under. */
    const val LEGACY_PERIODIC_SYNC_WORK_NAME = "step_sync_periodic"

    fun cleanUp(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_SYNC_WORK_NAME)
    }
}
