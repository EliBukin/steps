package com.example.stepsplit.data.trip

import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.domain.model.TripPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SeaLevelHeightsTest {

    private val points = listOf(
        TripPoint(1_000L, 31.778, 35.225, 5f, altitudeMeters = 100.0, speedMetersPerSecond = 1.2f),
        TripPoint(1_010L, 31.7781, 35.225, 5f, altitudeMeters = null, speedMetersPerSecond = 1.2f),
    )

    @Test
    fun `Android 13 and older can't convert, so the stored heights are kept as relative`() = runTest {
        assertNull(PlatformSeaLevelHeights(ApplicationProvider.getApplicationContext(), sdkInt = 33).toSeaLevel(points))
    }

    @Test
    fun `a trip without any heights has nothing to convert`() = runTest {
        val noHeights = points.map { it.copy(altitudeMeters = null) }
        assertEquals(noHeights, PlatformSeaLevelHeights(ApplicationProvider.getApplicationContext()).toSeaLevel(noHeights))
    }

    @Test
    fun `on Android 14 and newer the platform converter takes the geoid height off, and points without a height stay without`() = runTest {
        val converted = PlatformSeaLevelHeights(ApplicationProvider.getApplicationContext()).toSeaLevel(points)
        assertNotNull(converted)
        assertEquals(points.size, converted!!.size)
        // Jerusalem's geoid is about 19-20 m above the WGS84 ellipsoid (Robolectric's Android model says about 19 m).
        assertEquals(81.0, converted[0].altitudeMeters!!, 1.5)
        assertNull(converted[1].altitudeMeters)
        assertEquals(points[0].copy(altitudeMeters = converted[0].altitudeMeters), converted[0])
    }

    @Test
    fun `an impossible coordinate loses its height instead of keeping one on the other reference`() = runTest {
        val bad = points + TripPoint(1_020L, 95.0, 35.225, 5f, altitudeMeters = 100.0, speedMetersPerSecond = 1.2f)
        val converted = PlatformSeaLevelHeights(ApplicationProvider.getApplicationContext()).toSeaLevel(bad)!!
        assertEquals(81.0, converted[0].altitudeMeters!!, 1.5)
        assertNull(converted[2].altitudeMeters)
    }
}
