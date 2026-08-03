package com.example.stepsplit.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import com.example.stepsplit.StepSplitApplication
import com.example.stepsplit.ui.theme.StepSplitTheme
import com.example.stepsplit.util.ActivityRecognitionPermission

class MainActivity : ComponentActivity() {

    // No explicit result handling needed: the permission dialog closing triggers onResume, and
    // every screen already re-checks collection availability via LifecycleResumeEffect.
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as StepSplitApplication).container

        setContent {
            StepSplitTheme {
                StepSplitApp(
                    container = container,
                    onRequestPermission = { requestActivityRecognitionPermission() },
                )
            }
        }
    }

    private fun requestActivityRecognitionPermission() {
        if (ActivityRecognitionPermission.isRequiredOn(Build.VERSION.SDK_INT)) {
            requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
}
