package com.example.stepsplit.data.stepsource

import android.util.Log
import com.example.stepsplit.BuildConfig
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Production step source backed by Health Connect's on-device step counting - the app's sole
 * production [StepSource]. This version deliberately does not combine or merge several
 * simultaneously active acquisition paths (Local Recording, a direct `Sensor.TYPE_STEP_COUNTER`
 * listener, Activity Recognition transitions/sampling): one authoritative source only. If Health
 * Connect's on-device step counting is unavailable (older device, missing/outdated provider), this
 * reports a clear [StepSourceAvailability] state rather than silently falling back to anything
 * else - a fallback for older devices is a separate, future product decision, not something this
 * class does on its own.
 *
 * Health Connect's own platform-level pedometer collects continuously regardless of whether this
 * app is even running - unlike the old direct-sensor path, this class owns no foreground service
 * or live listener of its own. [readSteps] simply performs a foreground import of whatever Health
 * Connect already has, triggered by [com.example.stepsplit.data.repository.StepRepository]
 * immediately after permission is granted, whenever the app resumes, and periodically while the
 * Today screen is visible - see [com.example.stepsplit.ui.today.TodayViewModel].
 *
 * Every real Health Connect SDK call is made through [gateway] (never directly), and every
 * subscribe/read outcome is recorded to [healthStore] - the same [StepSourceHealthSink]/
 * [StepSourceHealthStore] infrastructure the old Local Recording source used, since the concern
 * ("did acquisition actually work, and when did it last see real data") is identical regardless of
 * which source produces it.
 *
 * [id] is deliberately the OLD Local Recording source's own id, not a new value - every existing
 * `step_buckets` row (real, field-collected production history, including rows currently marked
 * `REJECTED_UNVERIFIED`/`LEGACY_UNVERIFIED` by the now-removed validator) is keyed by it, and every
 * lifetime-stats/debug-diagnostics query in [com.example.stepsplit.data.repository.StepRepository]
 * filters by this id. Reusing it means this cutover needs no data migration at all: it stays the
 * one canonical identity per minute the product requirement calls for, existing history stays
 * immediately visible in lifetime totals, and there is no risk of the same minute ever being
 * imported twice under two different source ids and summed.
 */
class HealthConnectStepSource(
    private val gateway: HealthConnectGateway,
    private val healthStore: StepSourceHealthSink,
    private val clock: Clock = Clock.systemUTC(),
) : StepSource {

    override val id: String = SOURCE_ID

    override suspend fun checkAvailability(): StepSourceAvailability {
        if (!gateway.isOnDeviceStepCountingSupported()) return StepSourceAvailability.ApiUnavailable
        return when (gateway.sdkStatus()) {
            HealthConnectSdkStatus.AVAILABLE ->
                if (gateway.isReadStepsPermissionGranted()) {
                    StepSourceAvailability.Available
                } else {
                    StepSourceAvailability.PermissionNotGranted
                }
            HealthConnectSdkStatus.UPDATE_REQUIRED -> StepSourceAvailability.PlayServicesUpdateRequired
            HealthConnectSdkStatus.UNAVAILABLE -> StepSourceAvailability.ApiUnavailable
        }
    }

    /**
     * Health Connect needs no explicit subscription call of its own - the platform collects
     * regardless of what this method does. Still records a subscription outcome through
     * [healthStore] (mirroring the old Local Recording contract exactly) so the existing debug
     * diagnostics panel and [com.example.stepsplit.domain.model.deriveStepCollectionHealth] keep
     * working unchanged: "subscribed" here means "available and permitted to read right now".
     */
    override suspend fun ensureSubscribed(): Boolean {
        val availability = checkAvailability()
        val ok = availability is StepSourceAvailability.Available
        val now = clock.instant().epochSecond
        if (ok) {
            healthStore.recordSubscriptionSuccess(now)
        } else {
            val category = if (availability is StepSourceAvailability.PermissionNotGranted) {
                ApiFailureCategory.PERMISSION_OR_SECURITY_FAILURE
            } else {
                ApiFailureCategory.API_UNAVAILABLE
            }
            healthStore.recordSubscriptionFailure(ApiFailure(category), now)
        }
        return ok
    }

    override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
        val availability = checkAvailability()
        if (availability !is StepSourceAvailability.Available) {
            // Must not be conflated with a genuinely empty read - see StepSource.readSteps's own
            // doc comment and StepSourceUnavailableException.
            throw StepSourceUnavailableException(availability)
        }

        healthStore.recordReadAttempt(clock.instant().epochSecond, fromInclusive.epochSecond, toExclusive.epochSecond)

        val intervals = try {
            gateway.readStepIntervals(fromInclusive, toExclusive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = apiFailureForThrowable(e)
            healthStore.recordReadFailure(failure, clock.instant().epochSecond)
            debugLog { "readSteps: failed category=${failure.category} exception=${e::class.qualifiedName}: ${e.message}" }
            throw StepSourceReadException(e.message ?: "Health Connect read failed", failure, e)
        }

        val latestSampleEpochSecond = intervals.maxOfOrNull { it.endEpochSecond }
        healthStore.recordReadSuccess(intervals.size, latestSampleEpochSecond, clock.instant().epochSecond)
        return intervals
    }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    companion object {
        /** See this class's own doc comment on [id] for why this reuses the old Local Recording source's own id rather than introducing a new one. */
        const val SOURCE_ID = "local_recording_api"
        private const val TAG = "HealthConnectStepSrc"
    }
}
