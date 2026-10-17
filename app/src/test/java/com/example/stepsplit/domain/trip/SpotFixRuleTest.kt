package com.example.stepsplit.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotFixRuleTest {

    private val tripStart = 1_000L
    private val markedAt = 2_000L

    private fun fix(capturedAt: Long, accuracy: Float = 8f, speed: Float? = null, lat: Double = 31.77, lon: Double = 35.21) =
        RawLocationSample(latitude = lat, longitude = lon, accuracyMeters = accuracy, capturedAtEpochSecond = capturedAt, speedMetersPerSecond = speed)

    @Test
    fun `a usable fix has real coordinates and an accuracy of 50 m or better`() {
        assertTrue(SpotFixRule.isUsable(fix(markedAt)))
        assertTrue(SpotFixRule.isUsable(fix(markedAt, accuracy = 50f)))
        assertFalse("a Wi-Fi or cell estimate", SpotFixRule.isUsable(fix(markedAt, accuracy = 51f)))
        assertFalse("no accuracy reported", SpotFixRule.isUsable(fix(markedAt, accuracy = Float.MAX_VALUE)))
        assertFalse(SpotFixRule.isUsable(fix(markedAt, accuracy = 0f)))
        assertFalse(SpotFixRule.isUsable(fix(markedAt, accuracy = Float.NaN)))
        assertFalse("the 0,0 no-fix sentinel", SpotFixRule.isUsable(fix(markedAt, lat = 0.0, lon = 0.0)))
        assertFalse(SpotFixRule.isUsable(fix(markedAt, lat = 91.0)))
        assertFalse(SpotFixRule.isUsable(fix(markedAt, lon = Double.NaN)))
    }

    @Test
    fun `a candidate is within 30 s of the tap, either side, and from this trip`() {
        assertTrue(SpotFixRule.isCandidate(fix(markedAt - 30), markedAt, tripStart))
        assertTrue(SpotFixRule.isCandidate(fix(markedAt + 30), markedAt, tripStart))
        assertFalse(SpotFixRule.isCandidate(fix(markedAt - 31), markedAt, tripStart))
        assertFalse(SpotFixRule.isCandidate(fix(markedAt + 31), markedAt, tripStart))
        // A cached fix from before Start, even a recent one.
        assertFalse(SpotFixRule.isCandidate(fix(tripStart - 1), tripStart + 10, tripStart))
        assertTrue(SpotFixRule.isCandidate(fix(tripStart), tripStart + 10, tripStart))
        assertFalse(SpotFixRule.isCandidate(fix(markedAt, accuracy = 80f), markedAt, tripStart))
    }

    @Test
    fun `the closer candidate in time wins, before or after the tap`() {
        val old = fix(markedAt - 20)
        val recent = fix(markedAt - 3)
        val after = fix(markedAt + 2)
        assertSame(recent, SpotFixRule.closer(old, recent, markedAt, tripStart))
        assertSame(recent, SpotFixRule.closer(recent, old, markedAt, tripStart))
        assertSame(after, SpotFixRule.closer(recent, after, markedAt, tripStart))
        assertSame(old, SpotFixRule.closer(null, old, markedAt, tripStart))
        // Not a candidate: the best so far stays.
        assertSame(old, SpotFixRule.closer(old, fix(markedAt, accuracy = 90f), markedAt, tripStart))
        assertNull(SpotFixRule.closer(null, fix(markedAt - 60), markedAt, tripStart))
        // A tie keeps the one already chosen.
        val sameDistanceAfter = fix(markedAt + 3)
        assertSame(recent, SpotFixRule.closer(recent, sameDistanceAfter, markedAt, tripStart))
    }

    @Test
    fun `on foot any fix from the last 30 s is used at once`() {
        // 4.7 km/h for 30 s is 39 m.
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt - 30, speed = 1.3f), markedAt))
        assertTrue("5.4 km/h for 30 s is 45 m", SpotFixRule.needsCloserFix(fix(markedAt - 30, speed = 1.5f), markedAt))
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt - 25, speed = 1.3f), markedAt))
        // Standing still at a spring: the phone reports no speed, or zero.
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt - 28, speed = null), markedAt))
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt - 28, speed = 0f), markedAt))
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt - 28, speed = Float.NaN), markedAt))
    }

    @Test
    fun `in a jeep only a fix from the last few seconds is used at once`() {
        val fortyKmh = 40f / 3.6f
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt - 3, speed = fortyKmh), markedAt))
        assertTrue("4 s at 40 km/h is 44 m", SpotFixRule.needsCloserFix(fix(markedAt - 4, speed = fortyKmh), markedAt))
        assertTrue(SpotFixRule.needsCloserFix(fix(markedAt - 15, speed = fortyKmh), markedAt))
        // Captured at or after the tap: no later fix can be closer in time, so stop waiting.
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt, speed = fortyKmh), markedAt))
        assertFalse(SpotFixRule.needsCloserFix(fix(markedAt + 5, speed = fortyKmh), markedAt))
    }

    @Test
    fun `with no candidate there is always reason to wait`() {
        assertTrue(SpotFixRule.needsCloserFix(null, markedAt))
    }

    @Test
    fun `the limits are the documented ones`() {
        assertEquals(30L, SpotFixRule.MAX_FIX_AGE_SECONDS)
        assertEquals(40.0, SpotFixRule.MAX_DRIFT_METERS, 0.0)
        assertEquals(50f, SpotFixRule.MAX_ACCURACY_METERS, 0f)
        assertEquals(10_000L, SpotFixRule.WAIT_FOR_FIX_MILLIS)
    }

    @Test
    fun `the same fix is the same time and the same place, nothing else`() {
        val spotFix = RawLocationSample(31.7701, 35.2101, 6f, 1_000L, altitudeMeters = 812.0, speedMetersPerSecond = 11f)

        assertTrue(SpotFixRule.isSameFix(spotFix, 1_000L, 31.7701, 35.2101))
        assertTrue("accuracy, height and speed aren't stored with a spot", SpotFixRule.isSameFix(spotFix.copy(accuracyMeters = 9f, altitudeMeters = null, speedMetersPerSecond = null), 1_000L, 31.7701, 35.2101))
        assertFalse("a second later", SpotFixRule.isSameFix(spotFix, 1_001L, 31.7701, 35.2101))
        assertFalse("a metre away", SpotFixRule.isSameFix(spotFix, 1_000L, 31.7701 + 1e-5, 35.2101))
        assertFalse(SpotFixRule.isSameFix(spotFix, 1_000L, 31.7701, 35.2102))
    }
}
