package com.example.stepsplit.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.trip.service.TripRecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * A trip left ACTIVE by a recording that died with the app is marked interrupted as soon as the
 * app's screen opens - whichever tab it opens on - not only once the Trips tab is visited.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTripReconcileTest {

    private val app: StepSplitApplication = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ActivityController<MainActivity>>()

    @After
    fun tearDown() {
        controllers.forEach { it.destroy() }
        app.container.database.close()
    }

    private fun activeTrip(): Long = runBlocking {
        app.container.database.tripDao().insert(
            TripEntity(
                startEpochSecond = 1_000L,
                endEpochSecond = null,
                startZoneId = "UTC",
                state = TripState.ACTIVE.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = 1_000L,
            ),
        )
    }

    private fun stateOf(tripId: Long) = runBlocking { app.container.database.tripDao().getById(tripId)!!.state }

    /** Created only, not shown: the map's native code doesn't run on the JVM. */
    private fun openApp(intent: Intent = Intent(app, MainActivity::class.java)) {
        controllers += Robolectric.buildActivity(MainActivity::class.java, intent).create()
    }

    @Test
    fun `an ACTIVE trip with no recording is marked interrupted when the app opens, on any tab`() = runBlocking {
        assertFalse(TripRecordingService.isRunning)
        val tripId = activeTrip()

        // The way a GPX file opens the app: straight to the Map tab, which shows any ACTIVE trip as recording.
        val hike = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fhike.gpx")
        openApp(Intent(Intent.ACTION_VIEW).setClass(app, MainActivity::class.java).setDataAndType(hike, "application/gpx+xml"))

        withTimeout(5_000) { while (stateOf(tripId) != TripState.INTERRUPTED.name) delay(10) }
    }

    @Test
    fun `it runs once per process, not again when the screen is created again`() = runBlocking {
        val interrupted = activeTrip()
        openApp()
        withTimeout(5_000) { while (stateOf(interrupted) != TripState.INTERRUPTED.name) delay(10) }

        // A trip that is ACTIVE after that is this process's own recording (here, as the service would set it).
        val later = activeTrip()
        openApp()
        delay(300)
        assertEquals(TripState.ACTIVE.name, stateOf(later))
    }
}
