package com.example.stepsplit.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.health.connect.HealthConnectManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The screens the owner is sent to once Health Connect's own permission dialog no longer appears, and the order they're tried in. */
@RunWith(RobolectricTestRunner::class)
class HealthConnectPermissionScreenTest {

    private val packageName = "com.example.stepsplit.debug"

    @Test
    fun `on Android 14 and later Health Connect's page for this app comes first`() {
        val intents = healthConnectPermissionScreenIntents(packageName, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        assertEquals(
            listOf(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS, HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            intents.map { it.action },
        )
        assertEquals(packageName, intents[0].getStringExtra(Intent.EXTRA_PACKAGE_NAME))
        assertEquals(Uri.fromParts("package", packageName, null), intents[2].data)
    }

    @Test
    fun `on Android 13 and older Health Connect's settings come first`() {
        val intents = healthConnectPermissionScreenIntents(packageName, sdkInt = Build.VERSION_CODES.TIRAMISU)

        assertEquals(
            listOf(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            intents.map { it.action },
        )
    }

    @Test
    fun `the first screen the phone can open is the only one started`() {
        val candidates = healthConnectPermissionScreenIntents(packageName)
        val started = mutableListOf<Intent>()

        val opened = launchFirstAvailable(candidates) { started += it }

        assertSame(candidates[0], opened)
        assertEquals(listOf(candidates[0]), started)
    }

    @Test
    fun `when Health Connect refuses its page for this app, its settings open instead`() {
        // Stock Android 16: the page needs GRANT_RUNTIME_PERMISSIONS, which only the system holds.
        val candidates = healthConnectPermissionScreenIntents(packageName)
        val started = mutableListOf<Intent>()

        val opened = launchFirstAvailable(candidates) { intent ->
            if (intent.action == HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS) {
                throw SecurityException("requires android.permission.GRANT_RUNTIME_PERMISSIONS")
            }
            started += intent
        }

        assertSame(candidates[1], opened)
        assertEquals(listOf(candidates[1]), started)
    }

    @Test
    fun `a screen nothing handles, or one that refuses this app, falls through to the next`() {
        val candidates = healthConnectPermissionScreenIntents(packageName)
        val tried = mutableListOf<String?>()

        val opened = launchFirstAvailable(candidates) { intent ->
            tried += intent.action
            when (intent.action) {
                HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS -> throw ActivityNotFoundException()
                HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS -> throw SecurityException("not exported")
            }
        }

        assertSame(candidates[2], opened)
        assertEquals(candidates.map { it.action }, tried)
    }

    @Test
    fun `when nothing can be opened every screen is tried and nothing is returned`() {
        val candidates = healthConnectPermissionScreenIntents(packageName)
        val tried = mutableListOf<String?>()

        val opened = launchFirstAvailable(candidates) { intent ->
            tried += intent.action
            throw ActivityNotFoundException()
        }

        assertNull(opened)
        assertEquals(candidates.map { it.action }, tried)
    }
}
