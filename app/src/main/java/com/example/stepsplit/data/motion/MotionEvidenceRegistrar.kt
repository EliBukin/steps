package com.example.stepsplit.data.motion

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.stepsplit.util.ActivityRecognitionPermission
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Registers the Activity Recognition Transition API (for `ENTER`/`EXIT` of `IN_VEHICLE`,
 * `ON_BICYCLE`, `WALKING`, `RUNNING`, `STILL`) and the Sampling API (secondary safety signal and
 * bootstrap mechanism - the current activity may otherwise be unknown after app start, reboot, or a
 * late-arriving transition) against [MotionEvidenceReceiver]. Deliberately **not** tied to any
 * Activity's lifecycle - see [ensureRegistered]'s own doc comment for every trigger that calls it.
 *
 * Both registrations target the same [PendingIntent] class ([MotionEvidenceReceiver]) with different
 * request codes/actions, exactly the pattern [com.example.stepsplit.data.stepsource.LocalRecordingStepSource]
 * already uses for its own gateway-wrapped calls.
 */
class MotionEvidenceRegistrar(
    private val context: Context,
    private val gateway: ActivityRecognitionGateway,
    private val healthStore: MotionDiagnosticsHealthSink,
    private val clock: Clock,
) {
    /**
     * Idempotent - safe to call on every app process start, on every periodic sync, after a
     * detected registration failure, and from the boot/package-replaced receiver (see
     * `StepSplitApplication.onCreate`, `StepSyncWorker`, and [BootAndUpdateReceiver] respectively).
     * A missing/never-granted `ACTIVITY_RECOGNITION` permission is not an error here - it simply
     * means strict counting stays unavailable until the user grants it (surfaced via
     * `StepRepository.observeValidationHealth`), never silently falling back to unverified counting.
     */
    suspend fun ensureRegistered() {
        if (!ActivityRecognitionPermission.isGranted(context)) return
        registerTransitions()
        registerSampling()
    }

    private suspend fun registerTransitions() {
        try {
            gateway.requestTransitionUpdates(buildTransitionRequest(), transitionPendingIntent(context))
            healthStore.recordTransitionRegistrationSuccess(clock.instant().epochSecond)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthStore.recordTransitionRegistrationFailure(describeFailure(e), clock.instant().epochSecond)
        }
    }

    private suspend fun registerSampling() {
        try {
            gateway.requestActivityUpdates(SAMPLING_INTERVAL_MILLIS, samplingPendingIntent(context))
            healthStore.recordSamplingRegistrationSuccess(clock.instant().epochSecond)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthStore.recordSamplingRegistrationFailure(describeFailure(e), clock.instant().epochSecond)
        }
    }

    private fun describeFailure(e: Exception): MotionRegistrationFailure = when (e) {
        is ApiException -> MotionRegistrationFailure("API_EXCEPTION", e.statusCode)
        is SecurityException -> MotionRegistrationFailure("PERMISSION", null)
        else -> MotionRegistrationFailure("OTHER", null)
    }

    companion object {
        const val SAMPLING_INTERVAL_MILLIS = 15_000L
        const val ACTION_TRANSITION = "com.example.stepsplit.action.MOTION_TRANSITION"
        const val ACTION_SAMPLING = "com.example.stepsplit.action.MOTION_SAMPLING"
        private const val REQUEST_CODE_TRANSITION = 1001
        private const val REQUEST_CODE_SAMPLING = 1002

        /** `ENTER`+`EXIT` for every activity type the product requirement lists - 10 entries. */
        fun buildTransitionRequest(): ActivityTransitionRequest {
            val types = listOf(
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.WALKING,
                DetectedActivity.RUNNING,
                DetectedActivity.STILL,
            )
            val transitions = types.flatMap { type ->
                listOf(
                    ActivityTransition.Builder().setActivityType(type).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
                    ActivityTransition.Builder().setActivityType(type).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build(),
                )
            }
            return ActivityTransitionRequest(transitions)
        }

        /**
         * `FLAG_MUTABLE` is required, not optional: Play services attaches the actual
         * [com.google.android.gms.location.ActivityTransitionResult]/[com.google.android.gms.location.ActivityRecognitionResult]
         * extras to this intent when delivering it, and `FLAG_IMMUTABLE` silently breaks that
         * delivery on API 31+.
         */
        fun transitionPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MotionEvidenceReceiver::class.java).setAction(ACTION_TRANSITION)
            return PendingIntent.getBroadcast(context, REQUEST_CODE_TRANSITION, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }

        fun samplingPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MotionEvidenceReceiver::class.java).setAction(ACTION_SAMPLING)
            return PendingIntent.getBroadcast(context, REQUEST_CODE_SAMPLING, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }
}
