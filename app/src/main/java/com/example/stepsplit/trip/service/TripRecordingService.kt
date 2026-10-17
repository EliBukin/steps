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
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.stepsplit.R
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.data.trip.SpotMarkResult
import com.example.stepsplit.ui.MainActivity
import com.example.stepsplit.ui.trips.spotMarkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * [ACTION_MARK_SPOT], the notification's Mark spot action, is not a recording command at all and
 * never reaches the controller - see [handleMarkSpot].
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

    /**
     * Whether this service's foreground notification is up, guarded by [notificationLock]: a spot's
     * feedback (see [showSpotFeedback]) updates the notification from the main thread, while the
     * service can stop - and remove it - from a controller coroutine. Without the lock an update
     * could land just after the removal and leave a stray notification behind.
     */
    private val notificationLock = Any()
    private var notificationShown = false
    private var spotFeedbackReset: Job? = null

    /** Whether this service instance has had a recording command (anything but [ACTION_MARK_SPOT]) - see [handleMarkSpot]. */
    private var hadRecordingCommand = false

    private val commandController: TripRecordingCommandController by lazy {
        TripRecordingCommandController(
            repository = container.tripRepository,
            coordinator = container.tripRecordingCoordinator,
            locationClient = container.locationClient,
            clock = container.clock,
            onStopRequested = ::stopServiceForStartId,
            reconciliationScope = container.tripRecordingScope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MARK_SPOT) return handleMarkSpot(startId)
        hadRecordingCommand = true
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
        synchronized(notificationLock) { notificationShown = false }
        // Safety net for every terminal path, including one this class didn't itself initiate (e.g.
        // the system killing the service outright): commandController.shutdown() atomically closes
        // the command generation gate (permanently rejecting every existing/future command's attempt
        // to start, stop, or replace a collector from this instant on - see that method's own doc
        // comment) and stops whatever collector is currently running as the same atomic step, so the
        // location subscription can never outlive the service. Purely synchronous - it never blocks
        // this (main) thread on the suspending Room work its own trip reconciliation needs, which it
        // fires separately on a scope that outlives serviceScope.
        commandController.shutdown()
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
            removeForegroundNotification = {
                synchronized(notificationLock) {
                    notificationShown = false
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
            },
        )
    }

    /**
     * The notification's Mark spot action, sent as a plain `startService` by its [PendingIntent]
     * from the shade or the lock screen - the action doesn't ask to unlock (see [buildNotification]).
     * Not a recording command: it never calls [TripRecordingCommandController.beginCommand] (a new
     * generation would make the collector that is running look stale), never promotes or stops the
     * service, and only asks [com.example.stepsplit.data.trip.TripSpotMarker] - the same one the
     * Trips screen's button uses - for a spot. The outcome is shown as a toast and, for a few
     * seconds, as the notification's text.
     *
     * Normally the service is already recording. If this action alone started it (a notification
     * left over from a recording that is gone), there is nothing to mark: it says so and stops again.
     */
    private fun handleMarkSpot(startId: Int): Int {
        val startedForThisAlone = !hadRecordingCommand
        serviceScope.launch {
            val result = container.tripSpotMarker.markSpot()
            withContext(Dispatchers.Main) {
                showSpotFeedback(result)
                if (startedForThisAlone) stopSelf(startId)
            }
        }
        return if (startedForThisAlone) START_NOT_STICKY else START_STICKY
    }

    /** Main thread only. */
    private fun showSpotFeedback(result: SpotMarkResult) {
        val message = spotMarkMessage(resources, result)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (!updateNotificationIfShown(message)) return
        spotFeedbackReset?.cancel()
        spotFeedbackReset = serviceScope.launch(Dispatchers.Main) {
            delay(SPOT_FEEDBACK_MILLIS)
            updateNotificationIfShown(contentText = null)
        }
    }

    /** Re-posts the foreground notification with [contentText] (null: the usual text), only while it is up. Returns whether it was. */
    private fun updateNotificationIfShown(contentText: String?): Boolean = synchronized(notificationLock) {
        if (!notificationShown) return@synchronized false
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(contentText))
        true
    }

    private fun promoteToForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        synchronized(notificationLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            notificationShown = true
        }
    }

    /**
     * The recording notification's channel: default importance, but with no sound and no vibration,
     * so it never makes a noise. Not "silent" (low importance) as it used to be: Android hides silent
     * notifications on the lock screen unless the owner turns them on, and Mark spot must be there
     * with the phone locked in a jeep. A channel's importance can't be raised once it exists, so this
     * is a new channel and the old one is deleted.
     */
    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(SILENT_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trip_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The recording notification: tapping it opens the Trips tab; its actions are Mark spot and
     * Finish. [contentText] replaces the usual "Tap to view progress" - a spot's feedback.
     *
     * Public on the lock screen (its text says nothing private), so Mark spot is there even when the
     * phone hides notification content on a secure lock screen. Mark spot doesn't ask to unlock
     * ([NotificationCompat.Action.Builder.setAuthenticationRequired] false, also the default): with the
     * phone mounted in a jeep, one tap on the lock screen must be enough.
     */
    private fun buildNotification(contentText: String? = null): Notification {
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
        val markSpotIntent = PendingIntent.getService(
            this,
            MARK_SPOT_REQUEST_CODE,
            Intent(this, TripRecordingService::class.java).setAction(ACTION_MARK_SPOT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val markSpotAction = NotificationCompat.Action.Builder(0, getString(R.string.trip_notification_mark_spot_action), markSpotIntent)
            .setAuthenticationRequired(false)
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_trip)
            .setContentTitle(getString(R.string.trip_notification_title))
            .setContentText(contentText ?: getString(R.string.trip_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(markSpotAction)
            .addAction(0, getString(R.string.trip_notification_finish_action), finishIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.stepsplit.trip.action.START"
        const val ACTION_FINISH = "com.example.stepsplit.trip.action.FINISH"
        const val ACTION_RESUME = "com.example.stepsplit.trip.action.RESUME"
        const val ACTION_MARK_SPOT = "com.example.stepsplit.trip.action.MARK_SPOT"
        const val EXTRA_TRIP_ID = "com.example.stepsplit.trip.extra.TRIP_ID"
        @VisibleForTesting internal const val CHANNEL_ID = "trip_recording_lock_screen"

        /** The first, silent channel - see [ensureNotificationChannel]. */
        @VisibleForTesting internal const val SILENT_CHANNEL_ID = "trip_recording"
        internal const val NOTIFICATION_ID = 4201
        private const val MARK_SPOT_REQUEST_CODE = 1

        /** How long the notification shows a spot's feedback before its usual text comes back. */
        internal const val SPOT_FEEDBACK_MILLIS = 5_000L

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
