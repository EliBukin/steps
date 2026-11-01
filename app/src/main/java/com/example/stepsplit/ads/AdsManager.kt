package com.example.stepsplit.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.stepsplit.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The app's ads: Google's consent form first, where the law asks for one (the EEA, the UK and
 * Switzerland), then the Mobile Ads SDK. [ready] turns true once ads may be requested: Google's
 * User Messaging Platform says so ([ConsentInformation.canRequestAds]) and the SDK has started.
 * Only the Stats and History banners and the Trips list's card ask for ads, and only then.
 *
 * A release built without AdMob IDs ([BuildConfig.ADS_ENABLED] false) has no ads at all: no
 * consent request, the SDK never starts, and Settings offers no privacy options.
 */
class AdsManager(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val consentInformation: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(appContext) }
    private val mobileAdsStarted = AtomicBoolean(false)

    private val _ready = MutableStateFlow(false)

    /** True once ads may be requested; never in a build without ads. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _privacyOptionsRequired = MutableStateFlow(false)

    /** Whether Settings must offer Google's privacy options form, where the owner changes their ad consent. */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /**
     * At every start of the app's screen, as Google asks: refreshes the owner's consent status,
     * shows the consent form if it is required and not answered yet, then starts the SDK if ads may
     * be requested. Consent given at an earlier launch starts the SDK at once, while that runs.
     */
    fun gatherConsent(activity: Activity) {
        if (!BuildConfig.ADS_ENABLED) return
        consentInformation.requestConsentInfoUpdate(
            activity,
            consentRequestParameters(activity),
            {
                if (activity.isFinishing || activity.isDestroyed) {
                    onConsentChanged()
                } else {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        logFormError("consent form", formError)
                        onConsentChanged()
                    }
                }
            },
            { requestError ->
                logFormError("consent update", requestError)
                onConsentChanged()
            },
        )
        onConsentChanged()
    }

    /** Google's form for changing the consent given; Settings offers it while [privacyOptionsRequired]. */
    fun showPrivacyOptionsForm(activity: Activity) {
        if (!BuildConfig.ADS_ENABLED) return
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            logFormError("privacy options form", formError)
            onConsentChanged()
        }
    }

    private fun onConsentChanged() {
        _privacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consentInformation.canRequestAds()) startMobileAds()
    }

    /** Once per process, and off the main thread: the SDK's start reads from disk and the network. */
    private fun startMobileAds() {
        if (!mobileAdsStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            MobileAds.setRequestConfiguration(adRequestConfiguration())
            MobileAds.initialize(appContext) { _ready.value = true }
        }
    }

    private fun consentRequestParameters(context: Context): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        val geography = umpDebugGeography(BuildConfig.UMP_DEBUG_GEOGRAPHY)
        if (BuildConfig.DEBUG && geography != ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED) {
            val debugSettings = ConsentDebugSettings.Builder(context).setDebugGeography(geography)
            BuildConfig.UMP_TEST_DEVICE_HASHED_ID.takeIf { it.isNotEmpty() }?.let(debugSettings::addTestDeviceHashedId)
            builder.setConsentDebugSettings(debugSettings.build())
        }
        return builder.build()
    }

    private fun logFormError(what: String, error: FormError?) {
        if (error != null) Log.w(TAG, "$what: ${error.errorCode} ${error.message}")
    }

    private companion object {
        const val TAG = "AdsManager"
    }
}

/**
 * Debug builds' `-Pstepsplit.umpDebugGeography` (see app/build.gradle.kts): the region Google's
 * consent form behaves as if the phone were in. Empty or unknown: the phone's real region.
 */
internal fun umpDebugGeography(name: String): Int = when (name.trim().uppercase()) {
    "EEA" -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA
    "US_STATE" -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_REGULATED_US_STATE
    "OTHER" -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER
    else -> ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_DISABLED
}
