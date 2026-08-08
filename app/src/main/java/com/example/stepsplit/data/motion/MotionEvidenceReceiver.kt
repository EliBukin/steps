package com.example.stepsplit.data.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.stepsplit.StepSplitApplication
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import kotlinx.coroutines.launch

/**
 * Manifest-registered, `exported="false"` - only this app's own [android.app.PendingIntent]s
 * (created within this process, targeting this exact class - see [MotionEvidenceRegistrar]) ever
 * deliver to it, even though it's Play services calling back; the same reasoning already applied to
 * `TripRecordingService`'s own manifest declaration. Deliberately **not** tied to any Activity's
 * lifecycle - registered once via [MotionEvidenceRegistrar.ensureRegistered] (see that class's own
 * doc comment for every trigger), so this keeps receiving broadcasts while the UI is fully closed.
 *
 * Dispatches by result type - the same, documented pattern Google's own samples use - and does the
 * actual (potentially non-trivial, Room-touching) work via [goAsync] + a process-scoped coroutine,
 * since a [BroadcastReceiver.onReceive] call itself has only a few seconds of execution budget.
 * Converts raw GMS types to source-independent domain data here (the one place this app's code
 * touches [com.google.android.gms.location.ActivityTransitionEvent]/[com.google.android.gms.location.DetectedActivity]
 * directly), so [com.example.stepsplit.data.repository.StepRepository] never needs a [Context] or
 * GMS dependency of its own.
 */
class MotionEvidenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val container = (context.applicationContext as StepSplitApplication).container
        container.motionEvidenceScope.launch {
            try {
                when {
                    ActivityTransitionResult.hasResult(intent) -> {
                        val result = ActivityTransitionResult.extractResult(intent) ?: return@launch
                        val receiptContext = container.motionEvidenceConverter.captureReceiptContext()
                        val events = result.transitionEvents.map { event ->
                            val elapsedMillis = event.elapsedRealTimeNanos / 1_000_000
                            ConvertedTransitionEvent(
                                activityType = detectedActivityTypeOf(event.activityType),
                                isEnter = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                                eventElapsedRealtimeMillis = elapsedMillis,
                                bootSessionId = receiptContext.bootSessionId,
                                bootEpochOffsetMillis = receiptContext.bootEpochOffsetMillis,
                                derivedWallClockEpochMilli = container.motionEvidenceConverter.deriveWallClock(receiptContext, elapsedMillis),
                                receivedAtEpochMilli = receiptContext.wallClockAtReceiptMillis,
                                dedupeKey = "TRANSITION:${event.activityType}:${event.transitionType}:$elapsedMillis:${receiptContext.bootSessionId}",
                            )
                        }
                        container.stepRepository.ingestTransitionEvents(events)
                    }

                    ActivityRecognitionResult.hasResult(intent) -> {
                        val result = ActivityRecognitionResult.extractResult(intent) ?: return@launch
                        val receiptContext = container.motionEvidenceConverter.captureReceiptContext()
                        val batchId = "${receiptContext.bootSessionId}:${result.elapsedRealtimeMillis}"
                        val batch = ConvertedSampledBatch(
                            activities = result.probableActivities.map {
                                ConvertedSampledActivity(detectedActivityTypeOf(it.type), it.confidence)
                            },
                            eventElapsedRealtimeMillis = result.elapsedRealtimeMillis,
                            bootSessionId = receiptContext.bootSessionId,
                            bootEpochOffsetMillis = receiptContext.bootEpochOffsetMillis,
                            derivedWallClockEpochMilli = container.motionEvidenceConverter.deriveWallClock(receiptContext, result.elapsedRealtimeMillis),
                            receivedAtEpochMilli = receiptContext.wallClockAtReceiptMillis,
                            batchId = batchId,
                        )
                        container.stepRepository.ingestSampledBatch(batch)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
