package com.example.stepsplit.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.ui.navigation.Screen
import com.example.stepsplit.ui.theme.StepSplitTheme
import com.example.stepsplit.util.ActivityRecognitionPermission

class MainActivity : ComponentActivity() {

    // No explicit result handling needed: the permission dialog closing triggers onResume, and
    // every screen already re-checks collection availability via LifecycleResumeEffect.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

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

    private var pendingGpxDocumentResult: ((Uri?) -> Unit)? = null
    private val createGpxDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        pendingGpxDocumentResult?.invoke(uri)
        pendingGpxDocumentResult = null
    }

    private var initialRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialRoute = routeForIntent(intent)

        val container = (application as StepSplitApplication).container

        setContent {
            StepSplitTheme {
                StepSplitApp(
                    container = container,
                    onRequestPermission = { requestActivityRecognitionPermission() },
                    onRequestTripPermissions = ::requestTripPermissions,
                    onCreateGpxDocument = ::createGpxDocument,
                    initialRoute = initialRoute,
                )
            }
        }
    }

    // MainActivity is launchMode="singleTask" (see the manifest) so tapping the trip-recording
    // notification while the app is already running redelivers here instead of creating a second
    // instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialRoute = routeForIntent(intent)
    }

    private fun routeForIntent(intent: Intent?): String? =
        if (intent?.action == ACTION_OPEN_TRIPS) Screen.Trips.route else null

    private fun requestActivityRecognitionPermission() {
        if (ActivityRecognitionPermission.isRequiredOn(Build.VERSION.SDK_INT)) {
            requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
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

    private fun createGpxDocument(suggestedFileName: String, onResult: (Uri?) -> Unit) {
        pendingGpxDocumentResult = onResult
        createGpxDocumentLauncher.launch(suggestedFileName)
    }

    companion object {
        const val ACTION_OPEN_TRIPS = "com.example.stepsplit.action.OPEN_TRIPS"
    }
}
