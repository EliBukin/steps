package com.example.stepsplit.ui.ads

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.stepsplit.R
import com.example.stepsplit.ads.plainAdRequest
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.nativead.AdChoicesView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

/**
 * The Trips list's native ads for this visit (see [NativeAdSlots]): one per slot, each asked for
 * when its slot is first composed (see [NativeAdSlot]). Every one is destroyed when the screen
 * leaves; the next visit asks again.
 */
@Composable
internal fun rememberTripsNativeAds(adUnitId: String): NativeAdSlots<NativeAd> {
    val context = LocalContext.current
    val slots = remember(adUnitId) {
        NativeAdSlots<NativeAd>(
            maxAds = MAX_NATIVE_ADS_PER_VISIT,
            load = { onLoaded -> loadNativeAd(context, adUnitId, onLoaded) },
            destroy = NativeAd::destroy,
        )
    }
    DisposableEffect(slots) { onDispose(slots::destroyAll) }
    return slots
}

/**
 * One ad slot of the Trips list: asks for its own ad the first time it's composed, and shows it as
 * a card once it arrives. Until then it takes no room at all.
 */
@Composable
internal fun NativeAdSlot(slots: NativeAdSlots<NativeAd>, slot: Int) {
    LaunchedEffect(slots, slot) { slots.request(slot) }
    slots.adFor(slot)?.let { NativeAdCard(it) }
}

private fun loadNativeAd(context: Context, adUnitId: String, onLoaded: (NativeAd) -> Unit) {
    // Landscape pictures suit a card in a list best; the card fits any other shape too.
    val options = NativeAdOptions.Builder()
        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
        .build()
    AdLoader.Builder(context, adUnitId)
        .forNativeAd(onLoaded)
        .withNativeAdOptions(options)
        .build()
        .loadAd(plainAdRequest())
}

/**
 * [ad] as a card among the finished trips, shaped like them: its icon where a trip has its route
 * picture, a clear "Ad" label, the headline and text, the picture or video, and the ad's button,
 * with Google's AdChoices icon in the top end corner. Every asset Google sends is shown in the
 * view it registers it with, so taps on them open the ad.
 */
@Composable
private fun NativeAdCard(ad: NativeAd, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val mediaWidthPx = with(density) { (maxWidth - CARD_PADDING * 2).roundToPx() }
            val minMediaHeightPx = with(density) { MIN_MEDIA_SIZE.roundToPx() }
            AndroidView(
                factory = { context -> NativeAdView(context).apply { LayoutInflater.from(context).inflate(R.layout.native_ad_card, this, true) } },
                update = { view -> view.show(ad, colors, mediaWidthPx, minMediaHeightPx) },
                onRelease = NativeAdView::destroy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun NativeAdView.show(ad: NativeAd, colors: ColorScheme, mediaWidthPx: Int, minMediaHeightPx: Int) {
    val attribution = findViewById<TextView>(R.id.ad_attribution)
    attribution.setTextColor(colors.onTertiaryContainer.toArgb())
    attribution.background = rounded(colors.tertiaryContainer.toArgb(), radiusPx = 4 * resources.displayMetrics.density)

    val headline = findViewById<TextView>(R.id.ad_headline)
    headline.text = ad.headline
    headline.setTextColor(colors.onSurface.toArgb())
    headlineView = headline

    val body = findViewById<TextView>(R.id.ad_body)
    body.showText(ad.body, colors.onSurfaceVariant.toArgb())
    bodyView = body

    val advertiser = findViewById<TextView>(R.id.ad_advertiser)
    advertiser.showText(ad.advertiser, colors.onSurfaceVariant.toArgb())
    advertiserView = advertiser

    val icon = findViewById<ImageView>(R.id.ad_icon)
    val iconDrawable = ad.icon?.drawable
    icon.setImageDrawable(iconDrawable)
    icon.visibility = if (iconDrawable != null) View.VISIBLE else View.GONE
    iconView = icon

    val media = findViewById<MediaView>(R.id.ad_media)
    val content = ad.mediaContent
    if (content != null) {
        media.visibility = View.VISIBLE
        media.setImageScaleType(ImageView.ScaleType.FIT_CENTER)
        media.mediaContent = content
        // The picture's own shape, at least 120dp tall (Google's minimum) and at most a 16:9 band, so
        // a square or tall picture shows whole, letterboxed, without making the card a screen tall.
        val ratio = content.aspectRatio.takeIf { it > 0f } ?: MEDIA_MAX_HEIGHT_RATIO
        media.layoutParams = media.layoutParams.apply {
            val maxHeightPx = maxOf(minMediaHeightPx, (mediaWidthPx / MEDIA_MAX_HEIGHT_RATIO).toInt())
            height = (mediaWidthPx / ratio).toInt().coerceIn(minMediaHeightPx, maxHeightPx)
        }
    } else {
        media.visibility = View.GONE
    }
    mediaView = media

    adChoicesView = findViewById<AdChoicesView>(R.id.ad_choices)

    val callToAction = findViewById<TextView>(R.id.ad_call_to_action)
    callToAction.showText(ad.callToAction, colors.onPrimary.toArgb())
    callToAction.background = rounded(colors.primary.toArgb(), radiusPx = 20 * resources.displayMetrics.density)
    callToActionView = callToAction

    // Last, once every asset's view is set: the SDK registers them for clicks and impressions.
    setNativeAd(ad)
}

private fun TextView.showText(text: String?, color: Int) {
    this.text = text
    setTextColor(color)
    visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
}

private fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
}

private val CARD_PADDING = 12.dp

/** The smallest a native ad's media may be shown, by Google's native ad policy. */
private val MIN_MEDIA_SIZE = 120.dp

/** The media's tallest: a 16:9 band of the card's width. */
private const val MEDIA_MAX_HEIGHT_RATIO = 16f / 9f
