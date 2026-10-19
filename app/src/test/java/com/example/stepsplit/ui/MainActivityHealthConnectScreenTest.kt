package com.example.stepsplit.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.health.connect.HealthConnectManager
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.R
import com.example.stepsplit.StepSplitApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * *Open Health Connect* starts the best screen this phone has (see
 * [healthConnectPermissionScreenIntents]), tells the caller when the owner comes back so it can
 * re-check the permission, and never crashes when the phone has none of them.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityHealthConnectScreenTest {

    private val app: StepSplitApplication = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null
    private var returns = 0

    @Before
    fun setUp() {
        // Starting an intent nothing handles throws, as on a phone.
        shadowOf(app).checkActivities(true)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.container.database.close()
    }

    @Test
    fun `opens Health Connect's page for this app when the phone has it`() {
        handle(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
        handle(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, scheme = "package")
        val activity = launch()

        activity.openHealthConnectPermissions { returns++ }

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS, started.action)
        assertEquals(activity.packageName, started.getStringExtra(Intent.EXTRA_PACKAGE_NAME))
        assertEquals(0, returns)
    }

    @Test
    fun `falls back to the app's own settings page when Health Connect's screens are missing`() {
        handle(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, scheme = "package")
        val activity = launch()

        activity.openHealthConnectPermissions { returns++ }

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun `coming back from the screen tells the caller once`() {
        handle(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS)
        val activity = launch()
        activity.openHealthConnectPermissions { returns++ }

        // The screen covers the app, then the owner presses Back.
        controller!!.pause().stop()
        controller!!.restart().start().resume()
        assertEquals(1, returns)

        // Later visits away and back are not returns from Health Connect.
        controller!!.pause().stop()
        controller!!.restart().start().resume()
        assertEquals(1, returns)
    }

    /**
     * Android posts a result, and briefly pauses and resumes the app, for a screen it refused to
     * open - Health Connect's page for this app, on stock Android 16. That arrives while the next
     * screen is opening, before the owner has done anything there, so only a return after the app
     * was really covered counts.
     */
    @Test
    fun `a pause and resume while the screen is still opening is not a return`() {
        handle(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        val activity = launch()
        activity.openHealthConnectPermissions { returns++ }

        controller!!.pause().resume()

        assertEquals(0, returns)
    }

    @Test
    fun `a phone with none of the screens gets a message instead of a crash`() {
        val activity = launch()

        activity.openHealthConnectPermissions { returns++ }

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(app.getString(R.string.health_connect_screen_unavailable), ShadowToast.getTextOfLatestToast())
        assertEquals(0, returns)
    }

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }.get()

    /** Registers a screen of another app that handles [action], as Health Connect or Android's settings would. */
    private fun handle(action: String, scheme: String? = null) {
        val component = ComponentName("com.example.handler", "com.example.handler.Screen${action.hashCode()}")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                scheme?.let(::addDataScheme)
            },
        )
    }
}
