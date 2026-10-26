package com.example.stepsplit.data.trip

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.stepsplit.util.await
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The production [MapLocationClient], on Google Play services' fused location - the same location
 * service trip recording uses, so the privacy policy's description of where locations come from
 * holds for the map too.
 *
 * It asks for a fix every [UPDATE_INTERVAL_MILLIS] at high accuracy: often enough that the dot keeps
 * up with a jeep, while the screen is on anyway. With only approximate location allowed, Play
 * services sends approximate fixes instead, and the map shows a bigger circle around the dot.
 *
 * Play services shares fixes between requests: while a trip records and the map is open, the
 * recording may get a point as often as its own minimum interval allows
 * ([FusedTripLocationClient.MIN_UPDATE_INTERVAL_MILLIS]) instead of every 10 seconds. Its own
 * acceptance rules still decide which points it keeps.
 *
 * `@SuppressLint("MissingPermission")`: the Map tab collects this only with location
 * permission (fine or coarse). Without it, `requestLocationUpdates` throws [SecurityException] or
 * fails its `Task`, and either closes the flow with that exception rather than crashing.
 */
class FusedMapLocationClient(private val context: Context) : MapLocationClient {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    override suspend fun lastLocation(): MapFix? = try {
        (client.lastLocation.await() as Location?)?.toMapFix()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No permission, or Play services unavailable: the map waits for its first live fix instead.
        null
    }

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<MapFix> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toMapFix()) }
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

    companion object {
        const val UPDATE_INTERVAL_MILLIS = 2_000L
        const val MIN_UPDATE_INTERVAL_MILLIS = 1_000L

        /** Below this speed the phone's direction of travel is mostly noise, so the dot shows none. */
        const val MIN_SPEED_FOR_BEARING_METERS_PER_SECOND = 0.5f
    }
}

private fun Location.toMapFix() = MapFix(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    bearingDegrees = if (hasBearing() && hasSpeed() && speed >= FusedMapLocationClient.MIN_SPEED_FOR_BEARING_METERS_PER_SECOND) bearing else null,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
)
