package com.example.stepsplit.util

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the pure, [android.content.Context]-free half of [ActivityRecognitionPermission]
 * directly, so pre-29 vs 29+ behavior is pinned down without needing Robolectric to fake a
 * particular `Build.VERSION.SDK_INT` or a real permission grant state.
 */
class ActivityRecognitionPermissionTest {

    @Test
    fun `API 26-28 never require the runtime permission`() {
        assertFalse(ActivityRecognitionPermission.isRequiredOn(26))
        assertFalse(ActivityRecognitionPermission.isRequiredOn(27))
        assertFalse(ActivityRecognitionPermission.isRequiredOn(28))
    }

    @Test
    fun `API 29 and above require the runtime permission`() {
        assertTrue(ActivityRecognitionPermission.isRequiredOn(Build.VERSION_CODES.Q))
        assertTrue(ActivityRecognitionPermission.isRequiredOn(36))
    }

    @Test
    fun `on API 26-28 an ungranted permission is still satisfied - it must never block availability`() {
        assertTrue(ActivityRecognitionPermission.isSatisfied(sdkInt = 26, permissionGranted = false))
        assertTrue(ActivityRecognitionPermission.isSatisfied(sdkInt = 27, permissionGranted = false))
        assertTrue(ActivityRecognitionPermission.isSatisfied(sdkInt = 28, permissionGranted = false))
    }

    @Test
    fun `on API 29 and above satisfaction depends entirely on the actual grant state`() {
        assertFalse(ActivityRecognitionPermission.isSatisfied(sdkInt = 29, permissionGranted = false))
        assertTrue(ActivityRecognitionPermission.isSatisfied(sdkInt = 29, permissionGranted = true))
        assertFalse(ActivityRecognitionPermission.isSatisfied(sdkInt = 36, permissionGranted = false))
        assertTrue(ActivityRecognitionPermission.isSatisfied(sdkInt = 36, permissionGranted = true))
    }
}
