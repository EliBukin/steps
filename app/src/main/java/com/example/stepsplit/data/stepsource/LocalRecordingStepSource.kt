package com.example.stepsplit.data.stepsource

import android.content.Context
import android.util.Log
import com.example.stepsplit.BuildConfig
import com.example.stepsplit.util.ActivityRecognitionPermission
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.fitness.result.LocalDataReadResponse
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Production step source backed by the accountless "Recording API on mobile" -
 * [com.google.android.gms.fitness.FitnessLocal.getLocalRecordingClient], never the deprecated
 * account-based Google Fit / History API. Subscription is idempotent and intentionally never
 * routinely unsubscribed (unsubscribing makes previously recorded data unavailable). The Local
 * Recording API only retains a limited rolling history window on-device, which is why the
 * repository layer imports into Room rather than treating the API itself as long-term storage.
 *
 * The actual GMS calls are made through [gateway] (never directly), and every subscribe/read
 * outcome is recorded to [healthStore] - see that interface's own doc comment for why every such
 * call site is wrapped through [recordSafely] rather than called directly, and
 * [StepSourceHealthStore]'s doc comment for why a successful empty read must never be conflated
 * with genuine acquisition health - exactly what made "zero steps shown, no error visible" possible
 * before this existed: [ensureSubscribed] and [readSteps] used to collapse every failure down to a
 * bare `false`/thrown-with-generic-message, discarding the real Google status the whole time.
 *
 * [healthStore] is deliberately typed as [StepSourceHealthSink] - non-suspending - not
 * [StepSourceHealthRecorder], and deliberately has NO default value. Both are load-bearing: a
 * default like the old `StepSourceHealthRecorder = StepSourceHealthStore(context)` would silently
 * hand acquisition a recorder that suspends on real DataStore persistence the moment anyone
 * constructs this class without thinking about it - exactly the bug [AsyncStepSourceHealthRecorder]
 * exists to prevent. Requiring an explicit, non-suspending [StepSourceHealthSink] makes that
 * mistake a compile error instead of a latent runtime hazard: there is no default to fall back to,
 * and nothing suspend-shaped can satisfy the parameter's type at all.
 */
class LocalRecordingStepSource(
    private val context: Context,
    private val gateway: LocalRecordingGateway = PlayServicesLocalRecordingGateway(context),
    private val healthStore: StepSourceHealthSink,
    private val clock: Clock = Clock.systemUTC(),
) : StepSource {

    override val id: String = SOURCE_ID

    override suspend fun checkAvailability(): StepSourceAvailability {
        if (!ActivityRecognitionPermission.isGranted(context)) {
            return StepSourceAvailability.PermissionNotGranted
        }

        return try {
            when (
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                    context,
                    // The steps-only floor, not the general Local Recording minimum: this app
                    // only ever subscribes to TYPE_STEP_COUNT_DELTA (see ensureSubscribed()), and
                    // play-services-fitness documents the steps-only minimum as lower than the
                    // general one. Using the general constant would reject devices that support
                    // local step recording but not every other local fitness data type.
                    LocalRecordingClient.LOCAL_RECORDING_CLIENT_STEPS_MIN_VERSION_CODE,
                )
            ) {
                ConnectionResult.SUCCESS -> StepSourceAvailability.Available
                ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> StepSourceAvailability.PlayServicesUpdateRequired
                else -> StepSourceAvailability.ApiUnavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StepSourceAvailability.Error(e.message ?: "Google Play services check failed")
        }
    }

    override suspend fun ensureSubscribed(): Boolean {
        if (checkAvailability() !is StepSourceAvailability.Available) return false
        return subscribeAndRecordOutcome()
    }

    /**
     * The gateway-call-and-record-outcome half of [ensureSubscribed], deliberately independent of
     * [checkAvailability] (which needs a real/Robolectric [Context] and Play services shadow) so a
     * plain unit test can drive it directly against a fake [gateway] - see
     * `LocalRecordingStepSourceTest` for subscription-outcome and cancellation-propagation cases.
     *
     * The real subscribe outcome ([gateway.subscribe] succeeding or throwing) is decided entirely
     * before any [healthStore] call runs, and every [healthStore] call is wrapped through
     * [recordSafely] - a health-recording failure can therefore never turn a real success into a
     * reported failure (or vice versa); see [recordSafely]'s own doc comment.
     */
    internal suspend fun subscribeAndRecordOutcome(): Boolean = try {
        gateway.subscribe()
        recordSafely { healthStore.recordSubscriptionSuccess(clock.instant().epochSecond) }
        debugLog { "subscribe: success" }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = apiFailureForThrowable(e, ApiOperation.SUBSCRIBE)
        recordSafely { healthStore.recordSubscriptionFailure(failure, clock.instant().epochSecond) }
        debugLog { "subscribe: failed category=${failure.category} statusCode=${failure.statusCode}" }
        false
    }

    override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
        val availability = checkAvailability()
        if (availability !is StepSourceAvailability.Available) {
            // Must not be conflated with a genuinely empty read - see StepSource.readSteps's own
            // doc comment and StepSourceUnavailableException.
            throw StepSourceUnavailableException(availability)
        }

        val request = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.MINUTES)
            .setTimeRange(fromInclusive.epochSecond, toExclusive.epochSecond, TimeUnit.SECONDS)
            .build()

        return readAndRecordOutcome(request, fromInclusive.epochSecond, toExclusive.epochSecond)
    }

    /**
     * The gateway-call-and-record-outcome half of [readSteps], deliberately independent of
     * [checkAvailability] for the same reason as [subscribeAndRecordOutcome] - see that function's
     * own doc comment. [windowStartEpochSecond]/[windowEndEpochSecond] are recorded purely for the
     * debug diagnostics panel (see [StepSourceHealthSnapshot]) - they play no role in the actual
     * read or in what gets returned/thrown.
     *
     * As with [subscribeAndRecordOutcome], the real outcome - the parsed [RawStepInterval] list, or
     * whatever [gateway.readData] itself threw - is fully decided before any [healthStore] call
     * runs, and every [healthStore] call is wrapped through [recordSafely]: a health-recording
     * failure can never discard a successfully parsed [intervals] list, replace a real
     * [StepSourceReadException]'s category with something else, or prevent [gateway.readData] from
     * ever being attempted in the first place (the old, unwrapped `recordReadAttempt` call sat
     * before the read and would have blocked it outright had it thrown).
     */
    internal suspend fun readAndRecordOutcome(
        request: LocalDataReadRequest,
        windowStartEpochSecond: Long,
        windowEndEpochSecond: Long,
    ): List<RawStepInterval> {
        recordSafely { healthStore.recordReadAttempt(clock.instant().epochSecond, windowStartEpochSecond, windowEndEpochSecond) }
        debugLog { "readSteps: attempt window=[$windowStartEpochSecond, $windowEndEpochSecond)" }

        val intervals = try {
            gateway.readData(request).toRawIntervalsOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepSourceReadException) {
            // toRawIntervalsOrThrow already attaches a sanitized ApiFailure derived from the
            // response's own non-success status - see that function's own doc comment.
            val failure = e.apiFailure ?: ApiFailure(ApiFailureCategory.OTHER_API_FAILURE, null)
            recordSafely { healthStore.recordReadFailure(failure, clock.instant().epochSecond) }
            debugLog { "readSteps: failed (response status) category=${failure.category} statusCode=${failure.statusCode}" }
            throw e
        } catch (e: Exception) {
            // The Task itself failed (e.g. a thrown ApiException/SecurityException) rather than
            // completing with a non-success LocalDataReadResponse status - a different failure
            // shape, same sanitized category space (see ApiFailure.kt).
            val failure = apiFailureForThrowable(e, ApiOperation.READ)
            recordSafely { healthStore.recordReadFailure(failure, clock.instant().epochSecond) }
            debugLog { "readSteps: failed (task exception) category=${failure.category} statusCode=${failure.statusCode}" }
            throw StepSourceReadException(e.message ?: "Local Recording read failed", failure, e)
        }

        val latestSampleEpochSecond = intervals.maxOfOrNull { it.endEpochSecond }
        recordSafely { healthStore.recordReadSuccess(intervals.size, latestSampleEpochSecond, clock.instant().epochSecond) }
        debugLog { "readSteps: success intervals=${intervals.size} latestSample=$latestSampleEpochSecond" }
        return intervals
    }

    /**
     * Runs a single [healthStore] write as best-effort diagnostics: a [CancellationException] is
     * always rethrown (it represents genuine coroutine cancellation, not a health-recording problem,
     * and must never be silently swallowed), but any other exception is logged and ignored rather
     * than allowed to propagate. [healthStore] itself is [StepSourceHealthSink] - non-suspending, so
     * a call here can never actually block this coroutine on real persistence in the first place
     * (see that interface's own doc comment) - this exists purely as a defensive backstop against a
     * [StepSourceHealthSink] implementation throwing synchronously, so that even a badly-behaved one
     * still can never turn a successful subscription/read into a reported failure, discard
     * already-parsed step data before it reaches [com.example.stepsplit.data.repository.StepRepository],
     * or prevent the underlying [gateway] call from ever being attempted.
     */
    private inline fun recordSafely(record: () -> Unit) {
        try {
            record()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog { "health recording failed (ignored): ${e::class.simpleName}: ${e.message}" }
        }
    }

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    companion object {
        const val SOURCE_ID = "local_recording_api"
        private const val TAG = "LocalRecordingSource"
    }
}

/**
 * A completed [com.google.android.gms.tasks.Task] does not by itself mean the read succeeded -
 * play-services-fitness 21.3.0 can deliver a [LocalDataReadResponse] whose own
 * [LocalDataReadResponse.getStatus] is non-success even though the surrounding `Task` completed
 * normally (no exception, not cancelled). Silently treating that as "zero buckets" would be
 * indistinguishable from a genuinely empty window, and
 * [StepRepository][com.example.stepsplit.data.repository.StepRepository] would then record this
 * as a genuinely successful, empty sync instead of the read failure it actually was - masking the
 * real problem behind ordinary "nothing happened" collection health. This is
 * pulled out of [LocalRecordingStepSource.readSteps] into its own top-level function specifically
 * so a unit test can exercise it directly - a [LocalDataReadResponse] can be built with an
 * arbitrary failing [com.google.android.gms.common.api.Status] via its own public constructor,
 * with no Play Services connection, shadow, or mocking framework required. The thrown exception
 * also carries a sanitized [ApiFailure] derived from the same real status code - always with
 * [ApiOperation.READ], since this is always parsing a `readData` response - so a caller never has
 * to re-parse the message text to know why.
 */
internal fun LocalDataReadResponse.toRawIntervalsOrThrow(): List<RawStepInterval> {
    if (!status.isSuccess) {
        throw StepSourceReadException(
            "Local Recording read failed with status ${status.statusCode}: ${status.statusMessage}",
            apiFailureForStatusCode(status.statusCode, ApiOperation.READ),
        )
    }
    return buckets
        .asSequence()
        .flatMap { bucket -> bucket.dataSets }
        .flatMap { dataSet -> dataSet.dataPoints }
        .mapNotNull { point ->
            val steps = point.getValue(LocalField.FIELD_STEPS).asInt().toLong()
            if (steps <= 0) {
                null
            } else {
                RawStepInterval(
                    startEpochSecond = point.getStartTime(TimeUnit.SECONDS),
                    endEpochSecond = point.getEndTime(TimeUnit.SECONDS),
                    steps = steps,
                )
            }
        }
        .toList()
}

/**
 * Thrown when the Local Recording API reports a read failure - either through
 * [LocalDataReadResponse.getStatus] (see [toRawIntervalsOrThrow]) or because the underlying `Task`
 * itself failed (see [LocalRecordingStepSource.readSteps]) - caught like any other read failure by
 * [com.example.stepsplit.data.repository.StepRepository.syncNowLocked], which reports it as
 * [com.example.stepsplit.data.repository.SyncResult.Failed] without touching previously stored
 * data. [apiFailure] is the sanitized category/status code behind the failure, when known - never
 * exposed as raw text in release UI (see [com.example.stepsplit.domain.model.SyncFailureCategory],
 * which is the only thing shown there); [cause] is kept for debug-only logging, never surfaced to
 * the user.
 */
class StepSourceReadException(
    message: String,
    val apiFailure: ApiFailure? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
