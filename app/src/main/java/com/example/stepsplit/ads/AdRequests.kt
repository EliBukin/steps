package com.example.stepsplit.ads

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.RequestConfiguration

/**
 * The one ad request every ad in the app sends: a plain one, with nothing about the owner in it.
 * Google Play's Health Connect policy forbids using health data for advertising, and the app gives
 * the ad SDK none of its own data at all: no location, steps or trips, no keywords, content URLs or
 * custom targeting. Ads are chosen by Google's own signals only, under the consent the owner gave.
 */
fun plainAdRequest(): AdRequest = AdRequest.Builder().build()

/**
 * Settings for every ad: at most PG-rated (general audiences, with parental guidance), since this
 * is an everyday walking and outdoor app. No child-directed or under-age tag: the app isn't made
 * for children.
 */
internal fun adRequestConfiguration(): RequestConfiguration = RequestConfiguration.Builder()
    .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
    .build()
