package com.example.stepsplit.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.health.connect.HealthConnectManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient

/**
 * Screens where the owner can switch on Steps themselves, best first. Health Connect's own
 * permission dialog stops appearing after two "Don't allow" answers: a request then returns at once
 * with nothing granted, so the app has to send the owner to a screen where they can allow it.
 *
 * 1. Android 14+: Health Connect's page for this app, with its Steps switch. Stock Android 16
 *    refuses it to ordinary apps: Health Connect's entry point for it requires
 *    `GRANT_RUNTIME_PERMISSIONS`, which only the system holds (adb's shell does too, so it opens
 *    from `adb shell am start`). Starting it throws [SecurityException], and the next one opens.
 *    It stays first for any phone whose Health Connect lets it through.
 * 2. Health Connect's settings: its home on Android 14+, the separate Health Connect app on 13 and
 *    older. The owner goes to App permissions, then this app - the banner's hint says so.
 * 3. This app's page in Android's settings (Permissions, then "Health, fitness and wellness" on
 *    Android 16).
 *
 * Step counting needs Android 14 (see
 * [com.example.stepsplit.data.stepsource.HealthConnectGateway.isOnDeviceStepCountingSupported]),
 * so an older phone never gets as far as asking for permission.
 */
internal fun healthConnectPermissionScreenIntents(packageName: String, sdkInt: Int = Build.VERSION.SDK_INT): List<Intent> = buildList {
    // The second check is the one that guards the Android 14 constant; sdkInt only lets a test act as an older phone.
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, packageName))
    }
    add(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
    add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

/**
 * Starts the first of [candidates] the phone can open, and returns it; null when none can, and then
 * nothing was started. [launch] throws [ActivityNotFoundException] when nothing handles an intent,
 * and [SecurityException] when the screen that does won't open for this app: both move on to the
 * next. Starting is the check itself. A `resolveActivity` lookup first would need a `<queries>`
 * entry for every one of them, since package visibility hides other apps' screens from it.
 */
internal fun launchFirstAvailable(candidates: List<Intent>, launch: (Intent) -> Unit): Intent? =
    candidates.firstOrNull { intent ->
        try {
            launch(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
