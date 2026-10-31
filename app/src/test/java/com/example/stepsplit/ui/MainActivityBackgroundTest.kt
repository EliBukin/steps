package com.example.stepsplit.ui

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.data.trip.FakeTripLocationClient
import com.example.stepsplit.di.AppContainer
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * While a trip records, the process and the app's composition stay alive with the app in the
 * background. A screen's state must stop updating then, or the Trips and Map tabs keep re-reading
 * and re-smoothing the whole route on every fix, for hours (review 2, D1). The Trips tab's state
 * includes a one-second tick that reads the clock, so counting the clock's reads on the main
 * thread shows whether its flows still run.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityBackgroundTest {

    /** Counts the reads made on the main thread, where the screens' ViewModels run their flows. */
    private class CountingClock : Clock() {
        val mainThreadReads = AtomicInteger()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant {
            if (Looper.myLooper() == Looper.getMainLooper()) mainThreadReads.incrementAndGet()
            return Instant.ofEpochSecond(1_800_000_000L)
        }
    }

    private val app: StepSplitApplication = ApplicationProvider.getApplicationContext()
    private val clock = CountingClock()
    private lateinit var container: AppContainer
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        container = AppContainer(app, FakeTripLocationClient(), clock = clock)
        app.container = container
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        container.database.close()
    }

    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test
    fun `the Trips tab's state stops updating once the app is in the background`() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRIPS))
            .setup()
        advance(3)
        val whileShown = clock.mainThreadReads.get()
        assertTrue("the Trips tab ticks while it is on screen ($whileShown reads)", whileShown >= 2)

        // Home, or the screen locks: the activity stops; its ViewModel's flows stop 5 s later.
        controller!!.pause().stop()
        advance(6)
        val stopped = clock.mainThreadReads.get()
        advance(60)
        assertEquals("no work for a screen nobody sees", stopped, clock.mainThreadReads.get())

        // Back on screen, it updates again.
        controller!!.start().resume()
        advance(3)
        assertTrue(clock.mainThreadReads.get() > stopped)
    }

    @Test
    fun `every screen's state is collected with the lifecycle`() {
        // The Map tab's native map can't be drawn on the JVM, so the other tabs are checked in the source.
        val source = File("src/main/java/com/example/stepsplit/ui/StepSplitApp.kt").readText()
        assertFalse(Regex("""\.uiState\.collectAsState\(""").containsMatchIn(source))
        assertEquals(7, Regex("""\.uiState\.collectAsStateWithLifecycle\(\)""").findAll(source).count())
    }
}
