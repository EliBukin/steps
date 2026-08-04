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
