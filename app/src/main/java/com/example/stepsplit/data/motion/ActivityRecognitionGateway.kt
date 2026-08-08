package com.example.stepsplit.data.motion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import com.example.stepsplit.util.await
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransitionRequest

/**
 * Narrow seam around the four [com.google.android.gms.location.ActivityRecognitionClient] calls
 * this app makes, mirroring [com.example.stepsplit.data.stepsource.LocalRecordingGateway]'s own
 * reasoning: a real client cannot be meaningfully faked or constructed off-device, so tests drive a
 * [FakeActivityRecognitionGateway] instead.
 */
interface ActivityRecognitionGateway {
    suspend fun requestTransitionUpdates(request: ActivityTransitionRequest, pendingIntent: PendingIntent)
    suspend fun removeTransitionUpdates(pendingIntent: PendingIntent)
    suspend fun requestActivityUpdates(intervalMillis: Long, pendingIntent: PendingIntent)
    suspend fun removeActivityUpdates(pendingIntent: PendingIntent)
}

class PlayServicesActivityRecognitionGateway(context: Context) : ActivityRecognitionGateway {
    private val client by lazy { ActivityRecognition.getClient(context) }

    // The caller (MotionEvidenceRegistrar.ensureRegistered) already checks
    // ActivityRecognitionPermission.isGranted before ever reaching this gateway - lint's
    // MissingPermission check just can't trace a guard through that call chain (it only
    // recognizes a checkSelfPermission call directly in the same method as the @RequiresPermission
    // call it guards) - same reasoning and precedent as PlayServicesLocalRecordingGateway.subscribe.
    @SuppressLint("MissingPermission")
    override suspend fun requestTransitionUpdates(request: ActivityTransitionRequest, pendingIntent: PendingIntent) {
        client.requestActivityTransitionUpdates(request, pendingIntent).await()
    }

    @SuppressLint("MissingPermission")
    override suspend fun removeTransitionUpdates(pendingIntent: PendingIntent) {
        client.removeActivityTransitionUpdates(pendingIntent).await()
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestActivityUpdates(intervalMillis: Long, pendingIntent: PendingIntent) {
        client.requestActivityUpdates(intervalMillis, pendingIntent).await()
    }

    @SuppressLint("MissingPermission")
    override suspend fun removeActivityUpdates(pendingIntent: PendingIntent) {
        client.removeActivityUpdates(pendingIntent).await()
    }
}
