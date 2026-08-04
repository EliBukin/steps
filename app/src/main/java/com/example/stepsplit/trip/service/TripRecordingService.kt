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
import kotlinx.coroutines.launch

/**
 * Thin Android [Service] shell around [TripRecordingCommandController], which owns the actual
 * command-routing/race-prevention logic - see that class's own doc comment for why the split
 * exists. This class holds no reference to any Activity, ViewModel, or composable; its only job is
 * Android-specific mechanics: promote to foreground *immediately* in `onStartCommand` (before any
 * repository/coroutine work), maintain the ongoing notification, and translate each incoming
 * [Intent] into a controller call.
 *
 * Four distinct ways to arrive here, deliberately **not** treated identically - see
 * [TripRecordingCommandController] for how each is actually handled:
 * - [ACTION_START]: the user tapping "Start trip" - may create a brand-new trip.
 * - [ACTION_RESUME] (+ [EXTRA_TRIP_ID]): the user tapping "Resume" on a specific interrupted trip -
 *   the Trips screen has already re-validated precise-location permission and system location being
 *   enabled before sending this, the same validation a fresh Start goes through.
 * - [ACTION_FINISH]: the notification action or in-app Finish button.
 * - A **null** [Intent]: Android itself restarting an already-running `START_STICKY` service after
 *   process death - may only *recover* an existing ACTIVE trip, never create or resume one.
 *
 * There is no boot receiver anywhere in this app's manifest, so a device reboot can never reach
 * this class at all.
 *
 * A fifth path that is not a distinct command but can happen on any of the four above: foreground
 * promotion itself can fail (see `promoteToForeground`'s own comment). When it does, this command
 * never reaches [TripRecordingCommandController] at all - instead
 * [TripRecordingCommandController.handleForegroundPromotionFailure] runs, which honestly reconciles
 * any trip an *earlier* command left ACTIVE, since this service is about to stop with nothing left
 * collecting for it.
 */
class TripRecordingService : Service() {

    private val container get() = (application as StepSplitApplication).container
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingCommand: Job? = null

    private val commandController: TripRecordingCommandController by lazy {
        TripRecordingCommandController(
            repository = container.tripRepository,
            coordinator = container.tripRecordingCoordinator,
            locationClient = container.locationClient,
            clock = container.clock,
            onStopRequested = ::stopServiceForStartId,
        )
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val generation = commandController.beginCommand()

        try {
            // Before any slow work (repository/coroutine dispatch) - a foreground service must
            // promote itself immediately after being started. Deliberately a broad catch: both
            // SecurityException and (API 31+) ForegroundServiceStartNotAllowedException are
            // possible here.
            promoteToForeground()
        } catch (e: Exception) {
            // No durable trip state has been touched *by this command* at this point, but an
            // already-ACTIVE trip left over from an earlier command just lost its live recording
            // along with this failed promotion - see handleForegroundPromotionFailure's own doc
            // comment for why that must still be honestly reconciled, not just silently abandoned.
            pendingCommand?.cancel()
            pendingCommand = serviceScope.launch {
                commandController.handleForegroundPromotionFailure(generation, startId)
            }
            return START_NOT_STICKY
        }

        pendingCommand?.cancel()
        pendingCommand = serviceScope.launch {
            when {
                intent == null -> commandController.handleRestart(generation, startId)
                intent.action == ACTION_FINISH -> commandController.handleFinish(generation, startId)
                intent.action == ACTION_RESUME ->
                    commandController.handleResume(intent.getLongExtra(EXTRA_TRIP_ID, -1L), generation, startId)
                else -> commandController.handleStart(generation, startId)
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

    /**
     * [stopServiceIfOwned] confirms [android.app.Service.stopSelfResult] actually honored this
     * [startId] - Android's own independent guard, refusing the stop if a newer start command has
     * been delivered since - *before* the foreground notification is removed. Removing the
     * notification unconditionally first (the previous, buggy ordering) could leave the service
     * alive with no foreground notification, if a newer start had already superseded this one.
     */
    private fun stopServiceForStartId(startId: Int) {
        stopServiceIfOwned(
            startId = startId,
            stopSelfResult = ::stopSelfResult,
            removeForegroundNotification = { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) },
        )
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
        const val ACTION_RESUME = "com.example.stepsplit.trip.action.RESUME"
        const val EXTRA_TRIP_ID = "com.example.stepsplit.trip.extra.TRIP_ID"
        private const val CHANNEL_ID = "trip_recording"
        private const val NOTIFICATION_ID = 4201

        /**
         * In-process liveness flag, set true in [onCreate] and false in [onDestroy]. This is what
         * lets [com.example.stepsplit.data.trip.TripRepository.reconcileActiveTripOnLaunch] tell a
         * genuinely still-recording trip (this flag survived, because the service and the checking
         * code are running in the same live process) apart from one whose service died along with
         * the process and has not (yet, or ever) been recreated. Checking this immediately at app
         * launch has one inherent, documented race: if Android is about to restart this
         * START_STICKY service but simply hasn't yet at the moment of the check, the trip is
         * reported interrupted a little prematurely - an accepted MVP trade-off that needs
         * real-device confirmation, not a heartbeat/timeout mechanism speculatively built for a case
         * that may not matter in practice. This race is merely *premature*, not *harmful*: the
         * delayed restart that eventually does arrive finds no ACTIVE trip left to recover (see
         * [TripRecordingCommandController.handleRestart]) and simply stops itself, rather than
         * creating a duplicate.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
