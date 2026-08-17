package com.example.stepsplit.debug

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import com.example.stepsplit.data.stepsource.PlatformHealthConnectGateway

/**
 * Debug-only environment snapshot for the Settings debug diagnostics panel - the pieces of device
 * state a real-phone investigation actually needs (Health Connect provider status, whether the
 * device meets the on-device step-counting requirement) that neither
 * [com.example.stepsplit.data.stepsource.StepSourceHealthStore] nor
 * [com.example.stepsplit.data.stepsource.StepSourceAvailability] can see, since both only know
 * about the outcome of API calls already made - never a second acquisition path, purely read-only
 * inspection of already-installed environment state.
 */
data class DeviceDiagnosticsSnapshot(
    val androidRelease: String,
    val androidSdkInt: Int,
    val manufacturer: String,
    val model: String,
    val healthConnectAvailable: Boolean,
    /** Android 14+ with SDK extension level >= 20 - see [com.example.stepsplit.data.stepsource.HealthConnectGateway.isOnDeviceStepCountingSupported]. */
    val onDeviceStepCountingSupported: Boolean,
)

object DeviceDiagnostics {
    fun collect(context: Context): DeviceDiagnosticsSnapshot {
        val gateway = PlatformHealthConnectGateway(context)
        return DeviceDiagnosticsSnapshot(
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            androidSdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            healthConnectAvailable = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE,
            onDeviceStepCountingSupported = gateway.isOnDeviceStepCountingSupported(),
        )
    }
}
