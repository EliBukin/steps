package com.example.stepsplit.trip.service

/**
 * The exact decision behind [TripRecordingService]'s own stop path, extracted as a pure function so
 * the ordering it depends on is directly unit-testable without a real Android [android.app.Service]
 * (see `ServiceStopCoordinatorTest.kt`).
 *
 * [stopSelfResult] is Android's own independent guard against a stale `startId`: it returns `false`
 * if a newer start command has been delivered to the service since [startId], *without* stopping
 * anything. The foreground notification must only ever be torn down once that has actually confirmed
 * this [startId] owns termination - never before it, and never unconditionally. Removing it first and
 * only then discovering [stopSelfResult] refused the stop would leave the service alive with no
 * foreground notification, which is both a dishonest UI state and, on newer API levels, a platform
 * violation for a service that must stay in the foreground while it keeps running.
 *
 * Returns whatever [stopSelfResult] returned, so a caller can tell whether the stop was actually
 * honored.
 */
fun stopServiceIfOwned(
    startId: Int,
    stopSelfResult: (Int) -> Boolean,
    removeForegroundNotification: () -> Unit,
): Boolean {
    val owned = stopSelfResult(startId)
    if (owned) removeForegroundNotification()
    return owned
}

/**
 * Which startId [TripRecordingService]'s stop must name. The notification's Mark spot reaches the
 * service as a start command too, so it gets a startId of its own, but it is not a recording
 * command and must not make the recording's own stop look stale: naming the recording command's
 * startId after a Mark spot was delivered would make [android.app.Service.stopSelfResult] refuse
 * the stop, leaving the service and its "recording" notification up with nothing recording - after
 * a Finish, or after the recording failed.
 *
 * So a stop for the newest recording command names the newest startId of all, Mark spots
 * included; a stop for an older recording command keeps its own, which Android refuses, as it
 * should, because a newer recording command owns the service now. If a Mark spot was already on its
 * way when that stop was refused (Android counts a start before it reaches the service), the Mark
 * spot stops the service once it is done - see [stopAfterMarkSpot].
 *
 * Called from the main thread ([onStart], [stopAfterMarkSpot]) and from a command's coroutine
 * ([stopForRecordingCommand]), so one lock guards it all.
 */
class ServiceStartIds {
    private val lock = Any()
    private var latestStartId = 0
    private var latestRecordingStartId = 0
    private var recordingStopRequested = false

    /** Call at the very start of every `onStartCommand`, Mark spot included. */
    fun onStart(startId: Int, isRecordingCommand: Boolean) = synchronized(lock) {
        latestStartId = startId
        if (isRecordingCommand) {
            latestRecordingStartId = startId
            recordingStopRequested = false
        }
    }

    /** The stop a recording command asked for; [stop] is the actual stop, given the startId to name. Returns what [stop] returned. */
    fun stopForRecordingCommand(startId: Int, stop: (startId: Int) -> Boolean): Boolean = synchronized(lock) {
        if (startId != latestRecordingStartId) return@synchronized stop(startId)
        recordingStopRequested = true
        stop(latestStartId)
    }

    /** Call when Mark spot [startId] is done: stops the service if the recording already asked to stop. Returns whether it tried. */
    fun stopAfterMarkSpot(startId: Int, stop: (startId: Int) -> Boolean): Boolean = synchronized(lock) {
        if (!recordingStopRequested) return@synchronized false
        stop(startId)
        true
    }
}
