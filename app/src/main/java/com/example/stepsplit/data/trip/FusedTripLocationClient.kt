package com.example.stepsplit.data.trip

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.stepsplit.domain.trip.RawLocationSample
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.example.stepsplit.util.await
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Production [TripLocationClient] backed by [com.google.android.gms.location.FusedLocationProviderClient].
 * Request constants are centralized here, conservative, and documented so they can be tuned after
 * testing on a real hiking trail:
 *
 * - [UPDATE_INTERVAL_MILLIS] (10s): frequent enough to trace a walking/hiking route usefully
 *   without the battery cost of a 1-3s interval meant for turn-by-turn navigation.
 * - [MIN_UPDATE_DISTANCE_METERS] (8m): skips a fix if the device hasn't moved at least this far
 *   since the last one, so standing still doesn't keep producing (and draining battery on)
 *   redundant fixes - within the 5-10m range a hiking-oriented request should use.
 * - [MAX_UPDATE_DELAY_MILLIS] (15s): lets the platform batch fixes and deliver them together when
 *   that saves power, without risking a large data-loss window if recording finishes mid-batch.
 *
 * `@SuppressLint("MissingPermission")`: permission is the caller's responsibility - the Start-trip
 * flow in the Trips UI never starts a trip (and therefore never collects this) without first
 * confirming ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION is granted. If it is somehow missing
 * anyway, `requestLocationUpdates` throws [SecurityException] synchronously, which closes the flow
 * with that exception instead of crashing the process. `requestLocationUpdates` can *also* reject
 * registration asynchronously (its returned `Task` completing unsuccessfully - e.g. location
 * settings that can't satisfy the request) without throwing anything at the call site; the returned
 * `Task`'s failure listener (below) closes the flow with that cause too, so a caller collecting
 * [locationUpdates] is guaranteed to observe *either* an accepted registration or a failure, never
 * silence. See [TripRecordingCoordinator] for how a resulting flow failure is handled.
 */
class FusedTripLocationClient(private val context: Context) : TripLocationClient {

    // Shared (not one per locationUpdates() collection) so flush() below always targets the same
    // underlying client the active subscription, if any, is registered against.
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<List<RawLocationSample>> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_METERS)
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MILLIS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                trySend(result.locations.map { it.toRawLocationSample() })
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { e -> close(e) }
        } catch (e: SecurityException) {
            close(e)
        }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    /** Requests immediate delivery of any batched fixes; the delivery itself still arrives asynchronously through a still-active [locationUpdates] collection, if any. Never throws - a failed flush just means nothing extra arrives, not a fatal error while finishing a trip. */
    override suspend fun flush() {
        try {
            client.flushLocations().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // No location permission (shouldn't happen - see class doc comment) - nothing to flush.
        } catch (e: Exception) {
            // Best-effort only; a flush failure must never block or fail finishing a trip.
        }
    }

    companion object {
        const val UPDATE_INTERVAL_MILLIS = 10_000L
        const val MIN_UPDATE_INTERVAL_MILLIS = 5_000L
        const val MIN_UPDATE_DISTANCE_METERS = 8f
        const val MAX_UPDATE_DELAY_MILLIS = 15_000L
    }
}

private fun Location.toRawLocationSample() = RawLocationSample(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
    capturedAtEpochSecond = time / 1000L,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
)
