package com.example.stepsplit.ui

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PermissionsRationaleActivityTest {

    /** The policy's source, which the build copies into the assets. Unit tests run in the app module's folder. */
    private val policySource = File("../docs/privacy-policy.html")

    private fun start(): Pair<PermissionsRationaleActivity, WebView> {
        val activity = Robolectric.buildActivity(PermissionsRationaleActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val webView = findWebView(activity.window.decorView)
        assertNotNull("the screen shows the policy in a WebView", webView)
        return activity to webView!!
    }

    private fun findWebView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    @Test
    fun `shows the privacy policy from docs, as published`() {
        val (_, webView) = start()

        val loaded = shadowOf(webView).lastLoadDataWithBaseURL
        assertEquals(policySource.readText(), loaded.data)
        assertNull(loaded.baseUrl)
        assertEquals("text/html", loaded.mimeType)
    }

    @Test
    fun `loads nothing from the network`() {
        val (_, webView) = start()

        assertTrue(webView.settings.blockNetworkLoads)
        assertFalse(webView.settings.javaScriptEnabled)
        assertSame(PolicyWebViewClient, shadowOf(webView).webViewClient)
    }

    @Test
    fun `opens a tapped link in the browser, not in the WebView`() {
        val (activity, webView) = start()
        val link = Uri.parse("https://openfreemap.org")

        val handled = PolicyWebViewClient.shouldOverrideUrlLoading(webView, request(link))

        assertTrue(handled)
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals(link, opened.data)
    }

    @Test
    fun `the policy page needs nothing from the network to show`() {
        // The WebView blocks network loads, so a script, image, stylesheet or font fetched from
        // elsewhere would silently go missing in the app.
        val html = policySource.readText().lowercase()
        for (fetch in listOf("<script", "<img", "<link", "<iframe", "@import", "url(")) {
            assertFalse("privacy-policy.html must not use $fetch", fetch in html)
        }
    }

    private fun request(url: Uri) = object : WebResourceRequest {
        override fun getUrl(): Uri = url
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
