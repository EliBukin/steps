package com.example.stepsplit.debug

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.gms.fitness.LocalRecordingClient

/**
 * Debug-only environment snapshot for the Settings debug diagnostics panel - the pieces of device
 * state a real-phone investigation actually needs (per-device Play services version skew, whether
 * the hardware even exposes a step sensor at all) that neither [com.example.stepsplit.data.stepsource.StepSourceHealthStore]
 * nor [com.example.stepsplit.data.stepsource.StepSourceAvailability] can see, since both only know
 * about the outcome of API calls already made - never a second acquisition path, purely read-only
 * inspection of already-installed package/sensor state.
 */
data class DeviceDiagnosticsSnapshot(
    val androidRelease: String,
    val androidSdkInt: Int,
    val manufacturer: String,
    val model: String,
    /** Null if the Google Play services package itself could not be found (extremely unlikely, but not assumed). */
    val playServicesInstalledVersionName: String?,
    val playServicesInstalledVersionCode: Long?,
    /** [LocalRecordingClient.LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE] - the actual floor this app checks in [com.example.stepsplit.data.stepsource.LocalRecordingStepSource.checkAvailability]. */
    val playServicesRequiredMinVersionCode: Int,
    val hasStepCounterSensor: Boolean,
    val hasStepDetectorSensor: Boolean,
)

object DeviceDiagnostics {
    private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"

    fun collect(context: Context): DeviceDiagnosticsSnapshot {
        val playServicesPackageInfo = try {
            context.packageManager.getPackageInfo(GOOGLE_PLAY_SERVICES_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        return DeviceDiagnosticsSnapshot(
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            androidSdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            playServicesInstalledVersionName = playServicesPackageInfo?.versionName,
            playServicesInstalledVersionCode = playServicesPackageInfo?.let { PackageInfoCompat.getLongVersionCode(it) },
            playServicesRequiredMinVersionCode = LocalRecordingClient.LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE,
            hasStepCounterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null,
            hasStepDetectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null,
        )
    }
}
