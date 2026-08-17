package com.example.stepsplit.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.data.stepsource.READ_STEPS_PERMISSION
import com.example.stepsplit.ui.navigation.Screen
import com.example.stepsplit.ui.theme.StepSplitTheme
import java.util.concurrent.atomic.AtomicLong

/**
 * A one-shot, consumable navigation request from the trip-recording notification - [id] is
 * monotonically unique per tap (see [MainActivity.routeForIntent]) specifically so that two
 * consecutive taps targeting the *same* [route] still each produce a distinct event: a plain
 * `String?` route alone would make the second tap's state write structurally equal to the first's,
 * which Compose's snapshot state silently skips recomposing for, so the second navigation would
 * never fire.
 */
data class TripNotificationNavigationEvent(val route: String, val id: Long)

class MainActivity : ComponentActivity() {

    // Forwards the actual grant/deny result to whichever caller is currently waiting (set by
    // requestHealthConnectPermission below) - so the caller (TodayViewModel.refresh) can
    // immediately re-check availability and attempt an import rather than waiting on a
    // coincidental resume.
    private var pendingHealthConnectPermissionResult: ((Boolean) -> Unit)? = null
    private val requestHealthConnectPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions ->
        pendingHealthConnectPermissionResult?.invoke(grantedPermissions.contains(READ_STEPS_PERMISSION))
        pendingHealthConnectPermissionResult = null
    }

    // A single registered launcher, forwarded to whichever Compose-side caller is currently
    // waiting - registerForActivityResult must be called at a fixed point in the Activity's
    // lifecycle, but the Trips screen needs to trigger this request dynamically, potentially more
    // than once per Activity instance.
    private var pendingTripPermissionResult: ((Map<String, Boolean>) -> Unit)? = null
    private val requestTripPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        pendingTripPermissionResult?.invoke(result)
        pendingTripPermissionResult = null
    }

    private var navigationEvent by mutableStateOf<TripNotificationNavigationEvent?>(null)
    private val navigationEventCounter = AtomicLong(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        navigationEvent = eventForIntent(intent)

        val container = (application as StepSplitApplication).container

        setContent {
            StepSplitTheme {
                StepSplitApp(
                    container = container,
                    onRequestPermission = ::requestHealthConnectPermission,
                    onRequestTripPermissions = ::requestTripPermissions,
                    navigationEvent = navigationEvent,
                )
            }
        }
    }

    // MainActivity is launchMode="singleTask" (see the manifest) so tapping the trip-recording
    // notification while the app is already running redelivers here instead of creating a second
    // instance. Every redelivery - even a repeat tap that resolves to the same route as last time -
    // must still produce a *new* event id, or the second navigation would silently never fire (see
    // [TripNotificationNavigationEvent]'s own doc comment).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigationEvent = eventForIntent(intent)
    }

    private fun eventForIntent(intent: Intent?): TripNotificationNavigationEvent? =
        if (intent?.action == ACTION_OPEN_TRIPS) {
            TripNotificationNavigationEvent(Screen.Trips.route, navigationEventCounter.incrementAndGet())
        } else {
            null
        }

    /**
     * [onResult] receives the actual grant/deny outcome so the caller (see [StepSplitApp]'s own
     * wiring, and [com.example.stepsplit.ui.today.TodayViewModel.refresh]) can immediately
     * re-check availability and attempt an import rather than waiting on a coincidental resume -
     * see the class-level doc comment above [pendingHealthConnectPermissionResult]. This is a
     * completely separate permission flow from [requestTripPermissions] below: opening Today never
     * requests location, and starting a trip never touches Health Connect permission.
     */
    private fun requestHealthConnectPermission(onResult: (Boolean) -> Unit) {
        pendingHealthConnectPermissionResult = onResult
        requestHealthConnectPermissionLauncher.launch(setOf(READ_STEPS_PERMISSION))
    }

    private fun requestTripPermissions(onResult: (Map<String, Boolean>) -> Unit) {
        pendingTripPermissionResult = onResult
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestTripPermissionsLauncher.launch(permissions.toTypedArray())
    }

    companion object {
        const val ACTION_OPEN_TRIPS = "com.example.stepsplit.action.OPEN_TRIPS"
    }
}
