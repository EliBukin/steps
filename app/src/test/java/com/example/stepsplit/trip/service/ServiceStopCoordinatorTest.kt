package com.example.stepsplit.trip.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [stopServiceIfOwned] - the exact ordering [TripRecordingService.stopServiceForStartId]
 * delegates to - as a pure function with fake `stopSelfResult`/notification-removal callbacks. This
 * is a faithful extraction of the real Android [android.app.Service] behavior it's built on: real
 * `Service.stopSelfResult(startId)` returns `false`, doing nothing, if a newer start command has been
 * delivered to the service since `startId`; the fakes below model exactly that contract rather than
 * exercising a real `Service`/Robolectric shadow, so this proves the *ordering decision*
 * ([stopServiceIfOwned] itself), not genuine Android service-lifecycle behavior end to end - see
 * `TripRecordingCommandControllerTest.kt`'s own doc comment for the same distinction applied there.
 */
class ServiceStopCoordinatorTest {

    @Test
    fun `stopSelfResult confirming ownership removes the foreground notification`() {
        var notificationRemoved = false

        val owned = stopServiceIfOwned(
            startId = 1,
            stopSelfResult = { true },
            removeForegroundNotification = { notificationRemoved = true },
        )

        assertTrue(owned)
        assertTrue(notificationRemoved)
    }

    @Test
    fun `stopSelfResult rejecting the stop leaves the foreground notification untouched`() {
        var notificationRemoved = false

        val owned = stopServiceIfOwned(
            startId = 1,
            stopSelfResult = { false },
            removeForegroundNotification = { notificationRemoved = true },
        )

        assertFalse(owned)
        assertFalse(notificationRemoved)
    }

    /**
     * Faithfully models real `stopSelfResult` semantics: it returns `true` only for the *latest*
     * startId ever delivered, `false` for any older one - so a teardown request for an older startId
     * that arrives immediately after a newer start was delivered is correctly refused, and the
     * notification (which the newer, still-live start command still needs) is left alone.
     */
    @Test
    fun `a newer start arriving immediately before an older teardown request causes stopSelfResult to reject it, and the notification survives`() {
        var latestDeliveredStartId = 1
        var notificationRemoved = false
        val fakeStopSelfResult: (Int) -> Boolean = { requested -> requested == latestDeliveredStartId }

        // startId 2 is delivered (a newer Start/Finish/Resume/Restart command) before the teardown
        // request for the older startId 1 runs.
        latestDeliveredStartId = 2

        val owned = stopServiceIfOwned(
            startId = 1,
            stopSelfResult = fakeStopSelfResult,
            removeForegroundNotification = { notificationRemoved = true },
        )

        assertFalse(owned)
        assertFalse(notificationRemoved)
    }

    @Test
    fun `a teardown request for the latest delivered startId is honored and removes the notification`() {
        var latestDeliveredStartId = 1
        var notificationRemoved = false
        val fakeStopSelfResult: (Int) -> Boolean = { requested -> requested == latestDeliveredStartId }

        val owned = stopServiceIfOwned(
            startId = 1,
            stopSelfResult = fakeStopSelfResult,
            removeForegroundNotification = { notificationRemoved = true },
        )

        assertTrue(owned)
        assertTrue(notificationRemoved)
    }

    /**
     * Android's side of [ServiceStartIds]: it counts every start (Mark spot included) when it
     * schedules it, possibly before `onStartCommand` sees it, and `stopSelfResult` stops the
     * service only when it names the newest one counted.
     */
    private class FakeAndroid {
        var countedStartId = 0
        var stopped = false
        val ids = ServiceStartIds()

        fun deliver(isRecordingCommand: Boolean): Int {
            countedStartId++
            ids.onStart(countedStartId, isRecordingCommand)
            return countedStartId
        }

        fun stopSelfResult(startId: Int): Boolean = (startId == countedStartId).also { if (it) stopped = true }
    }

    @Test
    fun `a Mark spot delivered after the recording command doesn't make its stop look stale`() {
        val android = FakeAndroid()
        val start = android.deliver(isRecordingCommand = true)
        android.deliver(isRecordingCommand = false) // Mark spot from the notification

        assertTrue(android.ids.stopForRecordingCommand(start, android::stopSelfResult))
        assertTrue(android.stopped)
    }

    @Test
    fun `a newer recording command still makes an older one's stop be refused`() {
        val android = FakeAndroid()
        val start = android.deliver(isRecordingCommand = true)
        android.deliver(isRecordingCommand = false) // Mark spot
        android.deliver(isRecordingCommand = true) // Finish

        assertFalse(android.ids.stopForRecordingCommand(start, android::stopSelfResult))
        assertFalse(android.stopped)
    }

    @Test
    fun `a Mark spot counted but not yet delivered when the stop came stops the service once it is done`() {
        val android = FakeAndroid()
        val finish = android.deliver(isRecordingCommand = true)
        android.countedStartId++ // Mark spot counted by Android, not yet in onStartCommand
        assertFalse(android.ids.stopForRecordingCommand(finish, android::stopSelfResult))

        android.ids.onStart(android.countedStartId, isRecordingCommand = false)
        assertTrue(android.ids.stopAfterMarkSpot(android.countedStartId, android::stopSelfResult))
        assertTrue(android.stopped)
    }

    @Test
    fun `a Mark spot during a recording that goes on doesn't stop it`() {
        val android = FakeAndroid()
        android.deliver(isRecordingCommand = true)
        val markSpot = android.deliver(isRecordingCommand = false)

        assertFalse(android.ids.stopAfterMarkSpot(markSpot, android::stopSelfResult))
        assertFalse(android.stopped)
    }

    @Test
    fun `a recording command after a refused stop starts over`() {
        val android = FakeAndroid()
        val start = android.deliver(isRecordingCommand = true)
        android.countedStartId++ // a Resume, counted but not yet delivered
        assertFalse(android.ids.stopForRecordingCommand(start, android::stopSelfResult))
        android.ids.onStart(android.countedStartId, isRecordingCommand = true)

        val markSpot = android.deliver(isRecordingCommand = false)
        assertFalse("the Resume is recording now", android.ids.stopAfterMarkSpot(markSpot, android::stopSelfResult))
        assertEquals(false, android.stopped)
    }
}
