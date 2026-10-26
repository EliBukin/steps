package com.example.stepsplit.ui.map

import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.stepsplit.data.trip.MapFix
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whether the phone has a compass the Map tab can use: the rotation vector sensor (motion sensors and
 * magnetometer combined), else the magnetometer-only one.
 */
@Composable
internal fun rememberHasCompass(): Boolean {
    val context = LocalContext.current
    return remember(context) { compassSensor(context.getSystemService(SensorManager::class.java)) != null }
}

/**
 * Which way the phone points (see [HeadingMath.azimuthDegrees]), in degrees clockwise from true
 * north; null while unknown or not [enabled].
 *
 * The sensor runs only while [enabled] and the app is in the foreground: it stops when the app goes
 * to the background or this leaves the screen, and starts again on return. It needs no permission.
 * Magnetic north is turned into true north with the declination at [near] (the newest fix), about
 * 5 degrees in Israel; without a fix the heading stays magnetic.
 */
@Composable
internal fun rememberDeviceHeading(enabled: Boolean, near: MapFix?): State<Float?> {
    val context = LocalContext.current
    val view = LocalView.current
    val heading = remember { mutableStateOf<Float?>(null) }
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val sensor = remember(sensorManager) { compassSensor(sensorManager) }

    // A degree of latitude or longitude changes the declination by far less than a degree.
    val latitude = near?.latitude?.roundToInt()
    val longitude = near?.longitude?.roundToInt()
    val declination = remember(latitude, longitude) {
        if (latitude == null || longitude == null) 0f else GeomagneticField(latitude.toFloat(), longitude.toFloat(), 0f, System.currentTimeMillis()).declination
    }
    val currentDeclination by rememberUpdatedState(declination)

    LifecycleResumeEffect(enabled, sensor) {
        if (!enabled || sensor == null || sensorManager == null) {
            heading.value = null
            return@LifecycleResumeEffect onPauseOrDispose {}
        }
        val listener = object : SensorEventListener {
            private val matrix = FloatArray(9)
            private var smoothed: Float? = null

            override fun onSensorChanged(event: SensorEvent) {
                // Some phones add a fifth value (the heading's accuracy), which older Androids refused here.
                val vector = if (event.values.size > 4) event.values.copyOf(4) else event.values
                SensorManager.getRotationMatrixFromVector(matrix, vector)
                val magnetic = HeadingMath.azimuthDegrees(matrix, view.display?.rotation ?: 0) ?: return
                val reading = HeadingMath.normalize(magnetic + currentDeclination)
                val next = HeadingMath.smooth(smoothed, reading, SMOOTHING).also { smoothed = it }
                val shown = heading.value
                if (shown == null || abs(HeadingMath.shortestTurn(shown, next)) >= MIN_CHANGE_DEGREES) heading.value = next
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose {
            sensorManager.unregisterListener(listener)
            heading.value = null
        }
    }
    return heading
}

private fun compassSensor(sensorManager: SensorManager?): Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

/** Each reading moves the heading this much of the way: about a quarter-second lag at the sensor's ~15 readings a second. */
private const val SMOOTHING = 0.3f

/** Smaller changes aren't passed on, so a phone held still doesn't redraw the map all the time. */
private const val MIN_CHANGE_DEGREES = 0.5f
