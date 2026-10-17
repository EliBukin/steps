package com.example.stepsplit.trip.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.data.trip.FakeTripLocationClient
import com.example.stepsplit.di.AppContainer
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RawLocationSample
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowToast

/**
 * The notification's Mark spot action, end to end through the real [TripRecordingService]: the
 * action is on the recording notification, works without unlocking, marks a spot through the same
 * [com.example.stepsplit.data.trip.TripSpotMarker] as the Trips screen, and says so in a toast and,
 * for a few seconds, in the notification. The app's [AppContainer] gets a [FakeTripLocationClient]
 * in place of Fused Location; everything else is the real thing, Room included.
 *
 * The service's work runs on real background dispatchers and comes back to the main looper, which
 * Robolectric only runs when told - so [waitUntil] idles it while it waits.
 */
@RunWith(RobolectricTestRunner::class)
class TripRecordingServiceSpotTest {

    private lateinit var app: StepSplitApplication
    private lateinit var locationClient: FakeTripLocationClient
    private lateinit var container: AppContainer
    private var serviceController: ServiceController<TripRecordingService>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        locationClient = FakeTripLocationClient()
        container = AppContainer(app, locationClient)
        app.container = container
    }

    @After
    fun tearDown() {
        serviceController?.destroy()
        container.tripRecordingCoordinator.stop()
        container.database.close()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun createService(): TripRecordingService {
        val controller = Robolectric.buildService(TripRecordingService::class.java).create()
        serviceController = controller
        return controller.get()
    }

    private fun command(action: String) = Intent(app, TripRecordingService::class.java).setAction(action)

    /** A service recording a trip, as after the Trips screen's Start, with its notification up. */
    private fun recordingService(): TripRecordingService {
        val service = createService()
        service.onStartCommand(command(TripRecordingService.ACTION_START), 0, 1)
        waitUntil("the recording to start") { container.tripRecordingCoordinator.isActive && locationClient.activeSubscriptionCount > 0 }
        return service
    }

    private fun deliverFix(lat: Double): RawLocationSample {
        val fix = RawLocationSample(lat, 35.2101, 6f, Instant.now().epochSecond, altitudeMeters = 800.0, speedMetersPerSecond = 1.2f)
        locationClient.emit(listOf(fix))
        waitUntil("the fix to arrive") { container.tripRecordingCoordinator.latestFix.value == fix }
        return fix
    }

    private fun shownNotification(): Notification =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(TripRecordingService.NOTIFICATION_ID)

    private val Notification.text: String get() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun `the recording notification offers Mark spot, on the lock screen and without unlocking`() {
        val service = recordingService()

        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(listOf("Mark spot", "Finish"), notification.actions.map { it.title.toString() })
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        val markSpot = notification.actions.first()
        assertFalse(markSpot.isAuthenticationRequired)
        val pendingIntent = shadowOf(markSpot.actionIntent)
        assertTrue(pendingIntent.isServiceIntent)
        assertEquals(TripRecordingService.ACTION_MARK_SPOT, pendingIntent.savedIntent.action)
        assertEquals(TripRecordingService::class.java.name, pendingIntent.savedIntent.component?.className)
    }

    @Test
    fun `the recording channel shows on the lock screen by default and makes no sound`() {
        val manager = app.getSystemService(NotificationManager::class.java)
        // As left by an earlier version of the app.
        manager.createNotificationChannel(
            android.app.NotificationChannel(TripRecordingService.SILENT_CHANNEL_ID, "Trip recording", NotificationManager.IMPORTANCE_LOW),
        )

        val service = recordingService()

        val channel = manager.getNotificationChannel(TripRecordingService.CHANNEL_ID)
        // Silent (low) channels are hidden on the lock screen unless the owner turns them on.
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        assertEquals(TripRecordingService.CHANNEL_ID, shadowOf(service).lastForegroundNotification.channelId)
        assertNull("the old silent channel is gone", manager.getNotificationChannel(TripRecordingService.SILENT_CHANNEL_ID))
    }

    @Test
    fun `the Mark spot action marks a spot and says so in a toast and briefly in the notification`() {
        val service = recordingService()
        deliverFix(lat = 31.7701)
        val markSpotIntent = shadowOf(shadowOf(service).lastForegroundNotification.actions.first().actionIntent).savedIntent

        assertEquals(Service.START_STICKY, service.onStartCommand(markSpotIntent, 0, 2))
        waitUntil("the toast") { ShadowToast.getTextOfLatestToast() == "Spot 1 marked" }

        val tripId = container.tripRecordingCoordinator.recordingTripId!!
        val spot = runBlocking { container.tripRepository.getTripSpots(tripId) }.single()
        assertEquals(1, spot.number)
        assertEquals(31.7701, spot.latitude, 0.0)
        assertEquals("Spot 1 marked", shownNotification().text)
        assertEquals("the actions stay", 2, shownNotification().actions.size)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TripRecordingService.SPOT_FEEDBACK_MILLIS))
        assertEquals("Tap to view progress", shownNotification().text)
        assertTrue("still recording", container.tripRecordingCoordinator.isActive)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a second spot from the notification gets the next number`() {
        val service = recordingService()
        deliverFix(lat = 31.7701)
        val markSpot = command(TripRecordingService.ACTION_MARK_SPOT)

        service.onStartCommand(markSpot, 0, 2)
        waitUntil("the first spot") { ShadowToast.getTextOfLatestToast() == "Spot 1 marked" }
        service.onStartCommand(markSpot, 0, 3)
        waitUntil("the second spot") { ShadowToast.getTextOfLatestToast() == "Spot 2 marked" }

        assertEquals("Spot 2 marked", shownNotification().text)
    }

    /**
     * Mark spot is not a recording command: had it taken a command generation, the running collector
     * would look stale, and its failure would no longer interrupt the trip or stop the service.
     */
    @Test
    fun `marking a spot leaves the recording's own failure handling intact`() {
        val service = recordingService()
        deliverFix(lat = 31.7701)
        service.onStartCommand(command(TripRecordingService.ACTION_MARK_SPOT), 0, 2)
        waitUntil("the spot") { ShadowToast.getTextOfLatestToast() == "Spot 1 marked" }
        val tripId = container.tripRecordingCoordinator.recordingTripId!!

        locationClient.failActiveCollection(SecurityException("location permission revoked"))

        waitUntil("the trip to be interrupted") {
            runBlocking { container.tripRepository.getTrip(tripId) }?.state == TripState.INTERRUPTED.name
        }
        waitUntil("the service to stop") { shadowOf(service).isStoppedBySelf }
    }

    /**
     * Android honors `stopSelfResult(startId)` only for the newest start it has delivered, and the
     * notification's Mark spot reaches the service as a start too. Robolectric's `stopSelfResult`
     * always succeeds, so these check which startId the stop names: one older than the last Mark
     * spot would be refused on a phone, leaving the service and its "recording" notification up
     * with nothing recording.
     */
    @Test
    fun `a recording that fails after a Mark spot from the notification still stops the service`() {
        val service = recordingService()
        deliverFix(lat = 31.7701)
        service.onStartCommand(command(TripRecordingService.ACTION_MARK_SPOT), 0, 2)
        waitUntil("the spot") { ShadowToast.getTextOfLatestToast() == "Spot 1 marked" }

        locationClient.failActiveCollection(SecurityException("location permission revoked"))

        waitUntil("the service to stop") { shadowOf(service).isStoppedBySelf }
        assertEquals("the stop names the newest start", 2, shadowOf(service).stopSelfResultId)
    }

    @Test
    fun `a Mark spot from the notification while a Finish is in progress doesn't keep the service up`() {
        val service = recordingService()
        deliverFix(lat = 31.7701)
        val tripId = container.tripRecordingCoordinator.recordingTripId!!

        service.onStartCommand(command(TripRecordingService.ACTION_FINISH), 0, 2)
        waitUntil("the Finish to start waiting for late fixes") { locationClient.flushInvoked }
        service.onStartCommand(command(TripRecordingService.ACTION_MARK_SPOT), 0, 3)

        waitUntil("the trip to finish") { runBlocking { container.tripRepository.getTrip(tripId) }?.state == TripState.FINISHED.name }
        waitUntil("the service to stop") { shadowOf(service).isStoppedBySelf }
        assertEquals("the stop names the newest start", 3, shadowOf(service).stopSelfResultId)
    }

    @Test
    fun `Mark spot with nothing recording says so and stops the service again`() {
        val service = createService()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(command(TripRecordingService.ACTION_MARK_SPOT), 0, 1))
        waitUntil("the toast") { ShadowToast.getTextOfLatestToast() == "No trip is recording – spot not marked" }
        waitUntil("the service to stop") { shadowOf(service).isStoppedBySelf }

        assertNull("never promoted to the foreground", shadowOf(service).lastForegroundNotification)
    }
}
