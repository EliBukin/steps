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
 * Always started explicitly: either by the user tapping "Start trip" while the Trips screen is
 * visible ([ACTION_START], or a null action, treated the same way), the Finish notification action
 * or in-app Finish button ([ACTION_FINISH]), or Android itself restarting an already-running
 * START_STICKY service after process death (delivered as a null [Intent], again handled the same
 * as [ACTION_START] - see [com.example.stepsplit.data.trip.TripRepository.startTrip]'s idempotency,
 * which is exactly what makes that safe: whether this is a brand-new user-initiated trip or an OS
 * restart recovering an existing one, the same call resolves to the correct trip id either way).
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
            if (intent?.action == ACTION_FINISH) handleFinish() else handleStart()
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
        container.tripRecordingCoordinator.start(tripId)
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
         * mechanism speculatively built for a case that may not matter in practice.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
