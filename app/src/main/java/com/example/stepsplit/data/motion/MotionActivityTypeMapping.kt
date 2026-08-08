package com.example.stepsplit.data.motion

import com.example.stepsplit.domain.validation.MotionActivityType
import com.google.android.gms.location.DetectedActivity

/** Maps a [DetectedActivity] type constant to our own source-independent [MotionActivityType] - the only place this app's code ever touches the raw GMS integer constants. */
fun detectedActivityTypeOf(gmsType: Int): MotionActivityType = when (gmsType) {
    DetectedActivity.IN_VEHICLE -> MotionActivityType.IN_VEHICLE
    DetectedActivity.ON_BICYCLE -> MotionActivityType.ON_BICYCLE
    DetectedActivity.ON_FOOT -> MotionActivityType.ON_FOOT
    DetectedActivity.WALKING -> MotionActivityType.WALKING
    DetectedActivity.RUNNING -> MotionActivityType.RUNNING
    DetectedActivity.STILL -> MotionActivityType.STILL
    DetectedActivity.TILTING -> MotionActivityType.TILTING
    else -> MotionActivityType.UNKNOWN
}
