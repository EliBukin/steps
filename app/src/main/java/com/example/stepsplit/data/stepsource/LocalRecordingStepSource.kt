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
                    LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE,
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

        val response = client.readData(request).await()

        return response.buckets
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

    companion object {
        const val SOURCE_ID = "local_recording_api"
    }
}
