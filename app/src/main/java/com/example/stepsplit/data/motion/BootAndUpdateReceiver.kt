package com.example.stepsplit.data.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.stepsplit.StepSplitApplication
import kotlinx.coroutines.launch

/**
 * Manifest-registered, `exported="true"` (required for the OS-originated `BOOT_COMPLETED`/
 * `MY_PACKAGE_REPLACED` broadcasts to ever reach it). Restores motion-evidence registration after a
 * device reboot or an app update, and proactively closes any interval left open across the
 * boundary - see [com.example.stepsplit.data.motion.MotionEvidenceIngestor.handleTemporalDiscontinuity]'s
 * own doc comment for why this must happen even before any fresh Activity Recognition event
 * necessarily arrives (a stale open vehicle/bicycle interval must never be allowed to veto forever;
 * see `StrictStepValidationPolicy`'s own reboot-recovery doc comment).
 */
class BootAndUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val container = (context.applicationContext as StepSplitApplication).container
        container.motionEvidenceScope.launch {
            try {
                val bootSessionId = container.motionEvidenceConverter.captureReceiptContext().bootSessionId
                container.stepRepository.handleTemporalDiscontinuity(bootSessionId)
                container.motionEvidenceRegistrar.ensureRegistered()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
