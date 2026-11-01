package com.example.stepsplit.ads

import com.example.stepsplit.BuildConfig
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.ump.ConsentDebugSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdRequestsTest {

    @Test
    fun `the ad request carries nothing about the owner - no keywords, content URLs or targeting`() {
        val request = plainAdRequest()

        assertTrue(request.keywords.isEmpty())
        assertTrue(request.customTargeting.isEmpty)
        assertNull(request.contentUrl)
        assertTrue(request.neighboringContentUrls.isEmpty())
    }

    @Test
    fun `ads are at most PG-rated`() {
        assertEquals(RequestConfiguration.MAX_AD_CONTENT_RATING_PG, adRequestConfiguration().maxAdContentRating)
    }

    @Test
    fun `debug builds use Google's test ad units, and a release never does`() {
        val ids = listOf(BuildConfig.ADMOB_BANNER_UNIT_ID, BuildConfig.ADMOB_NATIVE_UNIT_ID)
        if (BuildConfig.DEBUG) {
            assertTrue(BuildConfig.ADS_ENABLED)
            assertEquals(listOf("ca-app-pub-3940256099942544/9214589741", "ca-app-pub-3940256099942544/2247696110"), ids)
        } else {
            ids.forEach { assertFalse(it, it.contains(GOOGLE_TEST_PUBLISHER)) }
            // Without admob.properties (as in CI and the gates) a release has no ads at all.
            if (!BuildConfig.ADS_ENABLED) assertEquals(listOf("", ""), ids)
        }
    }

    @Test
    fun `the consent form's debug region is read from the build's setting, and is off by default`() {
        assertEquals(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA, umpDebugGeography("EEA"))
        assertEquals(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA, umpDebugGeography(" eea "))
        assertEquals(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE, umpDebugGeography("US_STATE"))
        assertEquals(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER, umpDebugGeography("OTHER"))
        assertEquals(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED, umpDebugGeography(""))
        assertEquals(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED, umpDebugGeography("Mars"))
    }

    private companion object {
        const val GOOGLE_TEST_PUBLISHER = "3940256099942544"
    }
}
