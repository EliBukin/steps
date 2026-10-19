package com.example.stepsplit.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.stepsplit.R
import com.example.stepsplit.ui.theme.StepSplitTheme

/**
 * Health Connect requires every app that requests a health permission to declare a screen
 * explaining how that data is used, reachable from Health Connect's own "App permissions" ->
 * app -> "view usage" flow - see the manifest's `PermissionsRationaleActivity`/
 * `ViewPermissionUsageActivity` declarations. Without this, Health Connect refuses to actually
 * honor a granted `READ_STEPS` permission at read time (a real, field-verified failure: reads
 * threw `IllegalStateException: Incorrect health permission state` even though
 * `PermissionController.getGrantedPermissions()` reported the permission as granted).
 *
 * Health Connect's guide also requires this screen to display the same privacy policy as the Play
 * listing, so under a short summary it shows the whole policy: the build copies
 * `docs/privacy-policy.html`, the published page's source, into the assets (see
 * `CopyPrivacyPolicyTask` in `app/build.gradle.kts`). It's shown offline, in a WebView that loads
 * nothing from the network; a tapped link opens in the browser. Settings opens this same screen.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // As in MainActivity: without it the status bar keeps light icons over the light screen.
        enableEdgeToEdge()
        setContent {
            StepSplitTheme {
                PermissionsRationaleScreen(onBack = ::finish)
            }
        }
    }

    companion object {
        /** The privacy policy in the app's assets, copied there from `docs/privacy-policy.html` at build time. */
        const val PRIVACY_POLICY_ASSET = "privacy-policy.html"

        /** Where the policy is published - the address given in the Play listing. */
        const val PRIVACY_POLICY_URL = "https://elibukin.github.io/steps/privacy-policy.html"

        fun intent(context: Context): Intent = Intent(context, PermissionsRationaleActivity::class.java)
    }
}

@Composable
private fun PermissionsRationaleScreen(onBack: () -> Unit) {
    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
            }
            Column(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.health_permissions_rationale_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.health_permissions_rationale_body), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = buildAnnotatedString {
                        append(stringResource(R.string.privacy_policy_below_note))
                        append('\n')
                        val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                        withLink(LinkAnnotation.Url(PermissionsRationaleActivity.PRIVACY_POLICY_URL, TextLinkStyles(style = linkStyle))) {
                            append(PermissionsRationaleActivity.PRIVACY_POLICY_URL)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
            PrivacyPolicyView(modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** The privacy policy page from the app's assets. It scrolls on its own, under the summary. */
@Composable
private fun PrivacyPolicyView(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // The page is self-contained (inline styles, no scripts or images), so it needs nothing
                // from the network, and blocking it keeps the map tiles the app's only internet use.
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // The page paints its own background, light or dark; this avoids a white flash before it.
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = PolicyWebViewClient
                val html = context.assets.open(PermissionsRationaleActivity.PRIVACY_POLICY_ASSET).bufferedReader().use { it.readText() }
                // No base URL: the page's links are all absolute, and it loads nothing else.
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

/** Opens a link the user taps in the policy in the browser (or another app), never inside the WebView. */
internal object PolicyWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        openInBrowser(view.context, request.url)
        return true
    }

    private fun openInBrowser(context: Context, url: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: ActivityNotFoundException) {
            // No app can open it; the link just does nothing.
        }
    }
}
