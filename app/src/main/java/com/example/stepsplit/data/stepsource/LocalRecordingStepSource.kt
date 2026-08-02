package com.example.stepsplit.data.stepsource

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.stepsplit.util.await
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.fitness.result.LocalDataReadResponse
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Production step source backed by the accountless "Recording API on mobile" -
 * [FitnessLocal.getLocalRecordingClient], never the deprecated account-based Google Fit /
 * History API. Subscription is idempotent and intentionally never routinely unsubscribed
 * (unsubscribing makes previously recorded data unavailable). The Local Recording API only
 * retains a limited rolling history window on-device, which is why the repository layer imports
 * into Room rather than treating the API itself as long-term storage.
 */
class LocalRecordingStepSource(private val context: Context) : StepSource {

    override val id: String = SOURCE_ID

    private val client by lazy { FitnessLocal.getLocalRecordingClient(context) }

    override suspend fun checkAvailability(): StepSourceAvailability {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun readSteps(fromInclusive: Instant, toExclusive: Instant): List<RawStepInterval> {
        if (checkAvailability() !is StepSourceAvailability.Available) return emptyList()

        val request = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.MINUTES)
            .setTimeRange(fromInclusive.epochSecond, toExclusive.epochSecond, TimeUnit.SECONDS)
            .build()

        return client.readData(request).await().toRawIntervalsOrThrow()
    }

    companion object {
        const val SOURCE_ID = "local_recording_api"
    }
}

/**
 * A completed [com.google.android.gms.tasks.Task] does not by itself mean the read succeeded -
 * play-services-fitness 21.3.0 can deliver a [LocalDataReadResponse] whose own
 * [LocalDataReadResponse.getStatus] is non-success even though the surrounding `Task` completed
 * normally (no exception, not cancelled). Silently treating that as "zero buckets" would be
 * indistinguishable from a genuinely empty window, and
 * [StepRepository][com.example.stepsplit.data.repository.StepRepository] would then delete
 * previously stored buckets in its reconciliation window based on that false emptiness. This is
 * pulled out of [LocalRecordingStepSource.readSteps] into its own top-level function specifically
 * so a unit test can exercise it directly - a [LocalDataReadResponse] can be built with an
 * arbitrary failing [com.google.android.gms.common.api.Status] via its own public constructor,
 * with no Play Services connection, shadow, or mocking framework required.
 */
internal fun LocalDataReadResponse.toRawIntervalsOrThrow(): List<RawStepInterval> {
    if (!status.isSuccess) {
        throw StepSourceReadException(
            "Local Recording read failed with status ${status.statusCode}: ${status.statusMessage}",
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
 * Thrown when the Local Recording API reports a read failure through
 * [LocalDataReadResponse.getStatus] rather than by failing the underlying `Task` - caught like any
 * other read failure by [com.example.stepsplit.data.repository.StepRepository.syncNowLocked],
 * which reports it as [com.example.stepsplit.data.repository.SyncResult.Failed] without touching
 * previously stored data.
 */
class StepSourceReadException(message: String) : Exception(message)
