package com.example.stepsplit.data.motion

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.Clock

/**
 * Converts an elapsed-realtime-domain timestamp (as every Activity Recognition event reports) into
 * wall-clock time, and derives the OS boot count - the two raw ingredients
 * [com.example.stepsplit.data.repository.StepRepository] uses to detect a reboot or mid-boot clock
 * discontinuity (see `temporal_continuity_state`'s own doc comment). Both are captured together, in
 * one call, so they reflect a single consistent instant - `(wallClock - elapsedRealtime)` is only a
 * valid conversion offset for events received in the same boot as the instant it was measured.
 */
class MotionEvidenceConverter(private val context: Context, private val clock: Clock) {

    /** A single, internally-consistent snapshot of "now," captured once at receipt. */
    data class ReceiptContext(
        val bootSessionId: Long,
        val bootEpochOffsetMillis: Long,
        val wallClockAtReceiptMillis: Long,
    )

    fun captureReceiptContext(): ReceiptContext {
        val wallClockNow = clock.millis()
        val elapsedRealtimeNow = SystemClock.elapsedRealtime()
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        return ReceiptContext(
            bootSessionId = bootCount.toLong(),
            bootEpochOffsetMillis = wallClockNow - elapsedRealtimeNow,
            wallClockAtReceiptMillis = wallClockNow,
        )
    }

    /** Valid only when [eventElapsedRealtimeMillis] was measured in the same boot as [receiptContext] - see the class doc comment. */
    fun deriveWallClock(receiptContext: ReceiptContext, eventElapsedRealtimeMillis: Long): Long =
        receiptContext.bootEpochOffsetMillis + eventElapsedRealtimeMillis
}
