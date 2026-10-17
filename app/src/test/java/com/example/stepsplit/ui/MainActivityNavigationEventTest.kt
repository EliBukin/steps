package com.example.stepsplit.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.ui.navigation.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * The recording notification opens [MainActivity] on the Trips tab with a one-shot navigation
 * event. The activity keeps that launch intent for its whole life, and Android recreates it on a
 * rotation, a dark-mode or language switch, or after process death - a recreation must not open
 * Trips again, or the app jumps back to Trips from whatever tab the owner had moved to since.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityNavigationEventTest {

    private val app: StepSplitApplication = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.container.database.close()
    }

    private fun launchFromNotification(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRIPS))
            .setup()
            .also { controller = it }

    @Test
    fun `opening from the notification navigates to Trips`() {
        val activity = launchFromNotification().get()

        assertEquals(Screen.Trips.route, activity.navigationEventForTest?.route)
    }

    @Test
    fun `recreating the activity opened from the notification doesn't navigate to Trips again`() {
        val recreated = launchFromNotification().recreate().get()

        assertNull(recreated.navigationEventForTest)
    }

    @Test
    fun `a later tap on the notification still navigates to Trips`() {
        val activity = launchFromNotification().recreate().get()

        controller!!.newIntent(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRIPS))

        assertEquals(Screen.Trips.route, activity.navigationEventForTest?.route)
    }
}
