package com.example.stepsplit.data.stepsource

import android.annotation.SuppressLint
import android.content.Context
import com.example.stepsplit.util.await
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.fitness.result.LocalDataReadResponse

/**
 * Narrow seam around the two [LocalRecordingClient] calls [LocalRecordingStepSource] actually
 * makes, so a test can exercise subscribe/read outcome handling (exception -> [ApiFailure]
 * mapping, health-store updates, cancellation propagation) against a fake that throws whatever a
 * real GMS `Task` might, without a live Play Services connection or a mocking framework -
 * `LocalRecordingClient` itself cannot be meaningfully faked or constructed off-device.
 */
interface LocalRecordingGateway {
    /** Subscribes to step-count-delta local recording; throws on failure (mirrors the underlying `Task`). */
    suspend fun subscribe()

    /** Throws on failure; a successful [LocalDataReadResponse] can still carry a non-success internal status - see [toRawIntervalsOrThrow]. */
    suspend fun readData(request: LocalDataReadRequest): LocalDataReadResponse
}

/** Production [LocalRecordingGateway], backed by the real accountless Local Recording API client. */
class PlayServicesLocalRecordingGateway(context: Context) : LocalRecordingGateway {
    private val client by lazy { FitnessLocal.getLocalRecordingClient(context) }

    // The caller (LocalRecordingStepSource.ensureSubscribed) already checks
    // ActivityRecognitionPermission.isGranted via checkAvailability() before ever reaching this
    // gateway - lint's MissingPermission check just can't trace a guard through that call chain
    // (it only recognizes a checkSelfPermission call directly in the same method as the
    // @RequiresPermission call it guards).
    @SuppressLint("MissingPermission")
    override suspend fun subscribe() {
        client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).await()
    }

    override suspend fun readData(request: LocalDataReadRequest): LocalDataReadResponse =
        client.readData(request).await()
}
