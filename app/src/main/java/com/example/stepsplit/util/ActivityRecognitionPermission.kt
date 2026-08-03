package com.example.stepsplit.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * `ACTIVITY_RECOGNITION` became a runtime (dangerous) permission only at API 29 (Q) - on API
 * 26-28 the platform does not define it as a grantable runtime permission at all, and
 * [ContextCompat.checkSelfPermission] cannot be relied on to report `PERMISSION_GRANTED` for a
 * permission unknown to that platform version. Centralized here - rather than left as a raw
 * `SDK_INT` comparison duplicated in both [com.example.stepsplit.data.stepsource.LocalRecordingStepSource]
 * and [com.example.stepsplit.ui.MainActivity], which had already drifted out of sync (MainActivity
 * correctly gated its permission *request* on API 29+; LocalRecordingStepSource did not gate its
 * *check* the same way) - so "does this OS version need/accept the permission at all" has exactly
 * one answer app-wide.
 */
object ActivityRecognitionPermission {

    /** True from API 29 (Q) onward, where `ACTIVITY_RECOGNITION` is a real runtime permission. */
    fun isRequiredOn(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

    /**
     * Pure function of the two inputs that actually decide this, kept separate from [isGranted]
     * so a plain unit test can exercise every (sdkInt, permissionGranted) combination without a
     * real [Context].
     */
    fun isSatisfied(sdkInt: Int, permissionGranted: Boolean): Boolean =
        !isRequiredOn(sdkInt) || permissionGranted

    fun isGranted(context: Context): Boolean = isSatisfied(
        sdkInt = Build.VERSION.SDK_INT,
        permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED,
    )
}
