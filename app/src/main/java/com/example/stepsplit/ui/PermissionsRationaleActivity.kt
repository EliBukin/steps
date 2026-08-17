package com.example.stepsplit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.stepsplit.R
import com.example.stepsplit.ui.theme.StepSplitTheme

/**
 * Health Connect requires every app that requests a health permission to declare a screen
 * explaining how that data is used, reachable from Health Connect's own "App permissions" ->
 * app -> "view usage" flow - see the manifest's `PermissionsRationaleActivity`/
 * `ViewPermissionUsageActivity` declarations. Without this, Health Connect refuses to actually
 * honor a granted `READ_STEPS` permission at read time (a real, field-verified failure: reads
 * threw `IllegalStateException: Incorrect health permission state` even though
 * `PermissionController.getGrantedPermissions()` reported the permission as granted). Never part
 * of this app's own navigation - only ever launched by Health Connect itself.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StepSplitTheme {
                PermissionsRationaleScreen()
            }
        }
    }
}

@Composable
private fun PermissionsRationaleScreen() {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.health_permissions_rationale_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.health_permissions_rationale_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
