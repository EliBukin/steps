package com.example.stepsplit.ui.ads

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.stepsplit.ads.plainAdRequest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * A tab with a banner ad at its bottom, above the bottom navigation (the Stats and History tabs).
 * [content] gets the modifier that fills the room above the banner.
 */
@Composable
fun WithBannerAd(adsReady: Boolean, adUnitId: String, content: @Composable (Modifier) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        content(Modifier.weight(1f))
        BannerAd(adsReady = adsReady, adUnitId = adUnitId)
    }
}

/**
 * An anchored adaptive banner: Google's banner that spans the screen's width, with the height
 * Google picks for it. Outside the tab's scrolling list, so scrolling never moves it.
 *
 * It takes no room until ads may be requested ([adsReady]). Then it holds the banner's height
 * while the ad loads, so the ad arriving moves nothing, and gives the room back if no ad comes.
 * A later refresh that fails keeps the ad already showing. The banner pauses and resumes with the
 * tab, and is destroyed when the tab leaves the screen.
 */
@Composable
private fun BannerAd(adsReady: Boolean, adUnitId: String) {
    if (!adsReady || adUnitId.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val context = LocalContext.current
        val widthDp = maxWidth.value.toInt()
        val adSize = remember(widthDp) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp) }
        // A new width (the phone turned) makes a new banner of the new size.
        key(adSize) { Banner(adSize, adUnitId) }
    }
}

@Composable
private fun Banner(adSize: AdSize, adUnitId: String) {
    var noAd by remember { mutableStateOf(false) }
    if (noAd) return
    var adView by remember { mutableStateOf<AdView?>(null) }
    AndroidView(
        factory = { viewContext ->
            AdView(viewContext).apply {
                setAdSize(adSize)
                this.adUnitId = adUnitId
                adListener = object : AdListener() {
                    private var loaded = false
                    override fun onAdLoaded() {
                        loaded = true
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (!loaded) noAd = true
                    }
                }
                loadAd(plainAdRequest())
                adView = this
            }
        },
        modifier = Modifier.fillMaxWidth().height(adSize.height.dp),
        onRelease = AdView::destroy,
    )

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, adView) {
        val view = adView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.resume()
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
