package com.example.stepsplit.data.motion

import android.app.PendingIntent
import com.google.android.gms.location.ActivityTransitionRequest

/** Deterministic test double - see [ActivityRecognitionGateway]'s own doc comment for why a real client can't be faked directly. */
class FakeActivityRecognitionGateway : ActivityRecognitionGateway {
    var transitionRegistrations = 0
        private set
    var samplingRegistrations = 0
        private set
    var lastSamplingIntervalMillis: Long? = null
        private set
    var lastTransitionRequest: ActivityTransitionRequest? = null
        private set
    var throwOnTransitionRequest: Exception? = null
    var throwOnSamplingRequest: Exception? = null

    override suspend fun requestTransitionUpdates(request: ActivityTransitionRequest, pendingIntent: PendingIntent) {
        throwOnTransitionRequest?.let { throw it }
        transitionRegistrations++
        lastTransitionRequest = request
    }

    override suspend fun removeTransitionUpdates(pendingIntent: PendingIntent) {
        // No-op for tests - nothing currently asserts on removal.
    }

    override suspend fun requestActivityUpdates(intervalMillis: Long, pendingIntent: PendingIntent) {
        throwOnSamplingRequest?.let { throw it }
        samplingRegistrations++
        lastSamplingIntervalMillis = intervalMillis
    }

    override suspend fun removeActivityUpdates(pendingIntent: PendingIntent) {
        // No-op for tests.
    }
}
