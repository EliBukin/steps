package com.example.stepsplit.data.trip

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.stepsplit.domain.model.TripPoint
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a trip's stored heights into heights above sea level, for display and GPX, where the phone
 * can.
 *
 * [FusedTripLocationClient] stores `Location.getAltitude()`: the height above the WGS84 ellipsoid,
 * a smooth mathematical shape of the Earth, not above sea level. The two differ by the geoid height,
 * which is about +17 to +21 m across Israel (so a stored height there is about 20 m too high) and
 * anywhere from -106 to +85 m worldwide. GPX's `<ele>` and everything a person knows about heights
 * ("the Dead Sea is 430 m below sea level") mean above sea level.
 *
 * Nothing stored changes: the conversion runs each time a trip is shown, so trips recorded before
 * it existed are converted too.
 */
interface SeaLevelHeights {
    /**
     * [points] with every stored height replaced by the height above sea level at that point, in the
     * same order; points without a height stay without one, and a point the conversion rejects loses
     * its height rather than keep one on a different reference. Null when this phone can't convert
     * (Android 13 or older), or the conversion failed: the caller then keeps the stored heights and
     * treats them as relative.
     */
    suspend fun toSeaLevel(points: List<TripPoint>): List<TripPoint>?
}

/**
 * Android 14's own converter, `android.location.altitude.AltitudeConverter`. It looks up the geoid
 * height in a compact geoid model that ships with Android, reading it from the system's assets on
 * first use, so it runs on [Dispatchers.IO]. It needs only a latitude, longitude and ellipsoid
 * height, so a `Location` built from a stored point is enough.
 *
 * Android 13 and older have no converter, so [toSeaLevel] returns null there. The app doesn't ship a
 * geoid model of its own for them: the offset is fixed for a place, so it never changes the climb,
 * the descent or the chart's shape, only the absolute heights, which those phones then don't show.
 */
class PlatformSeaLevelHeights(
    context: Context,
    /** The phone's Android version; only tests pass another, to see what an older phone does. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : SeaLevelHeights {
    private val context = context.applicationContext

    override suspend fun toSeaLevel(points: List<TripPoint>): List<TripPoint>? {
        // The second check is the one that guards the Android 14 API; sdkInt only lets a test act as an older phone.
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        if (points.none { it.altitudeMeters != null }) return points
        return withContext(Dispatchers.IO) { convert(context, points) }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun convert(context: Context, points: List<TripPoint>): List<TripPoint>? {
        // One converter per trip: it caches the geoid tiles it has read, and a trip's points are close together.
        val converter = AltitudeConverter()
        return try {
            points.map { point ->
                val ellipsoidHeight = point.altitudeMeters ?: return@map point
                val location = Location(PROVIDER).apply {
                    latitude = point.latitude
                    longitude = point.longitude
                    altitude = ellipsoidHeight
                }
                try {
                    converter.addMslAltitudeToLocation(context, location)
                    point.copy(altitudeMeters = if (location.hasMslAltitude()) location.mslAltitudeMeters else null)
                } catch (e: IllegalArgumentException) {
                    // An impossible coordinate or a non-finite height: this point has no usable height.
                    point.copy(altitudeMeters = null)
                }
            }
        } catch (e: IOException) {
            // The system's geoid data couldn't be read: show the stored heights, as on older phones.
            null
        } catch (e: RuntimeException) {
            // Anything else from a vendor's build of the converter must not take the trip screen down.
            null
        }
    }

    private companion object {
        /** `Location` needs a provider name; this one is never registered or reported anywhere. */
        const val PROVIDER = "stored-trip-point"
    }
}
