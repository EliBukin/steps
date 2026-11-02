package com.example.stepsplit.ui.ads

import androidx.compose.runtime.mutableStateMapOf

/** The most native ads one visit to the Trips list asks for; slots further down stay empty. */
internal const val MAX_NATIVE_ADS_PER_VISIT = 5

/**
 * The native ads of one visit to the Trips list, one per ad slot. A native ad may be shown in only
 * one view at a time (its clicks and impressions are tracked through that view), so no two slots
 * ever share one.
 *
 * Nothing is asked for until a slot [request]s its ad, which it does the first time it's composed;
 * then that slot's ad, once [load] delivers it, is its [adFor] for the rest of the visit. Each slot
 * asks once, even if no ad comes, and at most [maxAds] slots ask. [destroyAll] when the screen
 * leaves destroys every ad, and any that arrives later.
 */
internal class NativeAdSlots<Ad : Any>(
    private val maxAds: Int,
    private val load: (onLoaded: (Ad) -> Unit) -> Unit,
    private val destroy: (Ad) -> Unit,
) {
    // Snapshot state: a slot's card shows as soon as its ad arrives.
    private val ads = mutableStateMapOf<Int, Ad>()
    private val requested = mutableSetOf<Int>()
    private var destroyed = false

    fun adFor(slot: Int): Ad? = ads[slot]

    fun request(slot: Int) {
        if (destroyed || slot in requested || requested.size >= maxAds) return
        requested += slot
        load { ad -> if (destroyed) destroy(ad) else ads[slot] = ad }
    }

    fun destroyAll() {
        destroyed = true
        ads.values.forEach(destroy)
        ads.clear()
    }
}
