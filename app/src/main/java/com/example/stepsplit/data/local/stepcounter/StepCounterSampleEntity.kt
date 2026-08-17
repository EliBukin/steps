package com.example.stepsplit.data.local.stepcounter

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One raw reading of [android.hardware.Sensor.TYPE_STEP_COUNTER] - a cumulative "steps since last
 * reboot" counter, never a delta by itself. [com.example.stepsplit.data.stepsource.SensorStepCounterSource]
 * turns consecutive rows *within the same [bootSessionId]* into [com.example.stepsplit.data.stepsource.RawStepInterval]
 * deltas; consecutive rows across different [bootSessionId]s are never diffed against each other,
 * since the underlying counter resets to 0 on reboot (see that class's own doc comment).
 *
 * The unique ([bootSessionId], [elapsedRealtimeMillisAtSample]) index makes a redelivered/duplicate
 * sensor callback (e.g. after [com.example.stepsplit.stepcounter.service.StepCounterService] is
 * restarted by the OS and immediately receives the current cumulative value again) an idempotent
 * no-op via [StepCounterSampleDao.insertIgnoringDuplicate] - the same convention already used by
 * `motion_evidence.dedupeKey`.
 *
 * [wallClockEpochMilli] is derived once, at receipt time, via
 * [com.example.stepsplit.data.motion.MotionEvidenceConverter] - the same elapsed-realtime-to-wall-clock
 * conversion already used for Activity Recognition evidence, so both feeds agree on "now" for the
 * same boot session.
 */
@Entity(
    tableName = "step_counter_samples",
    indices = [
        Index(value = ["bootSessionId", "elapsedRealtimeMillisAtSample"], unique = true),
        Index(value = ["wallClockEpochMilli"]),
    ],
)
data class StepCounterSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cumulativeSteps: Long,
    val elapsedRealtimeMillisAtSample: Long,
    val wallClockEpochMilli: Long,
    val bootSessionId: Long,
    val receivedAtEpochMilli: Long,
)
