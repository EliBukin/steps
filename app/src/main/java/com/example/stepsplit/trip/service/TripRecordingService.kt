package com.example.stepsplit.trip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.stepsplit.R
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Thin Android [Service] shell around [com.example.stepsplit.data.trip.TripRecordingCoordinator],
 * which owns the actual recording logic - see that class's own doc comment for why the split
 * exists. This class holds no reference to any Activity, ViewModel, or composable; its only job is
 * foreground-service lifecycle (promote to foreground immediately, before any slow work; maintain
 * the ongoing notification; react to Start/Finish commands) and delegating to the coordinator.
 *
 * Started three distinct ways, deliberately **not** treated identically:
 * - The user tapping "Start trip" while the Trips screen is visible, or the notification/in-app
 *   Finish action - both always deliver an explicit, non-null [Intent] with [ACTION_START] or
 *   [ACTION_FINISH] (see `TripsScreen.kt`). [handleStart] may create a brand-new trip via the
 *   idempotent [com.example.stepsplit.data.trip.TripRepository.startTrip].
 * - Android itself restarting an already-running `START_STICKY` service after process death,
 *   delivered as a **null** [Intent]. [handleRestart] may only *recover* an already-`ACTIVE` trip
 *   ([com.example.stepsplit.data.trip.TripRepository.getActiveTripId]) - it never calls `startTrip`.
 *   This distinction matters: if the app's own launch-time reconciliation
 *   (`TripsViewModel.init` -> `TripRepository.reconcileActiveTripOnLaunch`) already marked the trip
 *   `INTERRUPTED` before this delayed restart finally arrives, there is no longer an `ACTIVE` trip
 *   to recover - calling `startTrip()` at that point would silently create and start recording a
 *   *second*, unrelated trip. [handleRestart] instead finds nothing to recover and stops the
 *   service without creating anything.
 *
 * There is no boot receiver anywhere in this app's manifest, so a device reboot can never reach
 * this class at all.
 */
class TripRecordingService : Service() {

    private val container get() = (application as StepSplitApplication).container
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingCommand: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before any slow work (repository/coroutine dispatch) - a foreground service must promote
        // itself immediately after being started.
        promoteToForeground()

        pendingCommand?.cancel()
        pendingCommand = serviceScope.launch {
            when {
                intent == null -> handleRestart()
                intent.action == ACTION_FINISH -> handleFinish()
                else -> handleStart()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // Safety net for every terminal path, including one this class didn't itself initiate
        // (e.g. the system killing the service outright): the location subscription must never
        // outlive the service.
        container.tripRecordingCoordinator.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun handleStart() {
        val tripId = container.tripRepository.startTrip()
        container.tripRecordingCoordinator.start(tripId) { throwable -> handleRecordingFailure(tripId, throwable) }
    }

    /**
     * Handles Android redelivering a null [Intent] to restart this `START_STICKY` service after
     * process death - see the class doc comment for why this must never call `startTrip()`. Only
     * ever resumes an already-`ACTIVE` trip; if none exists (e.g. it was already reconciled to
     * `INTERRUPTED` by the time this delayed restart arrives), this stops the service without
     * creating or changing any trip.
     */
    private suspend fun handleRestart() {
        val tripId = container.tripRepository.getActiveTripId()
        if (tripId == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        container.tripRecordingCoordinator.start(tripId) { throwable -> handleRecordingFailure(tripId, throwable) }
    }

    /**
     * Requests a flush and waits a short, bounded grace period for any already-batched fixes to
     * arrive through the still-active collector before marking the trip finished - "flush when
     * practical, never wait indefinitely." [com.example.stepsplit.data.trip.TripRepository.recordAcceptedBatch]
     * is a further backstop regardless: even a fix that arrives after [finishTrip][com.example.stepsplit.data.trip.TripRepository.finishTrip]
     * runs here is simply dropped as stale, never appended.
     */
    private suspend fun handleFinish() {
        val tripId = container.tripRepository.getActiveTripId()
        if (tripId != null) {
            container.locationClient.flush()
            delay(FINISH_FLUSH_GRACE_PERIOD_MILLIS)
            container.tripRepository.finishTrip(tripId)
        }
        container.tripRecordingCoordinator.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Invoked by [com.example.stepsplit.data.trip.TripRecordingCoordinator] at most once if the
     * location flow itself fails (see that class's `onFailure` doc comment) - the coordinator has
     * already stopped collecting by the time this runs. Marks the trip honestly `INTERRUPTED`
     * rather than leaving it `ACTIVE` with nothing actually recording behind it, and tears down the
     * foreground notification/service exactly like a normal Finish.
     */
    private suspend fun handleRecordingFailure(tripId: Long, @Suppress("UNUSED_PARAMETER") throwable: Throwable) {
        container.tripRepository.markTripInterrupted(tripId)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trip_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_TRIPS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val finishIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, TripRecordingService::class.java).setAction(ACTION_FINISH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_trip)
            .setContentTitle(getString(R.string.trip_notification_title))
            .setContentText(getString(R.string.trip_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.trip_notification_finish_action), finishIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.stepsplit.trip.action.START"
        const val ACTION_FINISH = "com.example.stepsplit.trip.action.FINISH"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 4201
        private const val FINISH_FLUSH_GRACE_PERIOD_MILLIS = 2_000L

        /**
         * In-process liveness flag, set true in [onCreate] and false in [onDestroy]. This is what
         * lets [com.example.stepsplit.data.trip.TripRepository.reconcileActiveTripOnLaunch] tell a
         * genuinely still-recording trip (this flag survived, because the service and the checking
         * code are running in the same live process) apart from one whose service died along with
         * the process and has not (yet, or ever) been recreated. Checking this immediately at app
         * launch has one inherent, documented race: if Android is about to restart this
         * START_STICKY service but simply hasn't yet at the moment of the check, the trip is
         * reported interrupted a little prematurely - an accepted MVP trade-off (see that
         * function's own doc comment) that needs real-device confirmation, not a heartbeat/timeout
         * mechanism speculatively built for a case that may not matter in practice. Once
         * [handleRestart] and [handleStart]'s split exists, this race is merely *premature*, not
         * *harmful*: the delayed restart that eventually does arrive finds no ACTIVE trip left to
         * recover and simply stops itself, rather than creating a duplicate.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
