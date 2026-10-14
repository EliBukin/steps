package com.example.stepsplit.ui.trips

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricScaleTest {

    @Test
    fun `picks the largest round distance that fits, with its exact width`() {
        val step = MetricScale.choose(metersPerDp = 3.0, maxWidthDp = 100f)!!

        // 100 dp covers 300 m here; the largest of 1-2-5 x 10^n under that is 200 m.
        assertEquals(200, step.meters)
        assertEquals(200 / 3.0, step.widthDp.toDouble(), 1e-4)
    }

    @Test
    fun `a round distance that exactly fills the bar is used`() {
        val step = MetricScale.choose(metersPerDp = 5.0, maxWidthDp = 100f)!!

        assertEquals(500, step.meters)
        assertEquals(100f, step.widthDp, 1e-4f)
    }

    @Test
    fun `distances of a kilometer and more stay whole kilometers`() {
        assertEquals(2_000, MetricScale.choose(metersPerDp = 25.0, maxWidthDp = 100f)!!.meters)
        assertEquals(10_000, MetricScale.choose(metersPerDp = 120.0, maxWidthDp = 100f)!!.meters)
    }

    @Test
    fun `fully zoomed out still shows the largest step rather than nothing`() {
        val step = MetricScale.choose(metersPerDp = 1_000_000.0, maxWidthDp = 100f)!!

        assertEquals(5_000_000, step.meters)
        assertEquals(5f, step.widthDp, 1e-4f)
    }

    @Test
    fun `nothing is drawn when even one meter is wider than the bar`() {
        assertNull(MetricScale.choose(metersPerDp = 0.005, maxWidthDp = 100f))
    }

    @Test
    fun `nothing is drawn before the map reports a usable scale`() {
        assertNull(MetricScale.choose(metersPerDp = 0.0, maxWidthDp = 100f))
        assertNull(MetricScale.choose(metersPerDp = -1.0, maxWidthDp = 100f))
        assertNull(MetricScale.choose(metersPerDp = Double.NaN, maxWidthDp = 100f))
        assertNull(MetricScale.choose(metersPerDp = Double.POSITIVE_INFINITY, maxWidthDp = 100f))
        assertNull(MetricScale.choose(metersPerDp = 3.0, maxWidthDp = 0f))
    }

    @Test
    fun `across every zoom level the bar never overflows and shows only 1, 2 or 5 times a power of ten`() {
        var metersPerDp = 0.01
        while (metersPerDp < 100_000.0) {
            val step = MetricScale.choose(metersPerDp, maxWidthDp = 100f)
            if (step != null) {
                assertTrue("width ${step.widthDp} at $metersPerDp m/dp", step.widthDp <= 100f + 1e-3f)
                var leading = step.meters
                while (leading % 10 == 0) leading /= 10
                assertTrue("${step.meters} m is not a round 1-2-5 step", leading in setOf(1, 2, 5))
            }
            metersPerDp *= 1.07
        }
    }

    @Test
    fun `labels stay in meters below a kilometer, including a half-meter midpoint`() {
        assertEquals(ScaleLabel("250", ScaleUnit.METERS), MetricScale.label(250.0, Locale.US))
        assertEquals(ScaleLabel("500", ScaleUnit.METERS), MetricScale.label(500.0, Locale.US))
        assertEquals(ScaleLabel("2.5", ScaleUnit.METERS), MetricScale.label(2.5, Locale.US))
        assertEquals(ScaleLabel("0.5", ScaleUnit.METERS), MetricScale.label(0.5, Locale.US))
    }

    @Test
    fun `labels switch to kilometers from one kilometer, with at most one decimal`() {
        assertEquals(ScaleLabel("1", ScaleUnit.KILOMETERS), MetricScale.label(1_000.0, Locale.US))
        assertEquals(ScaleLabel("2.5", ScaleUnit.KILOMETERS), MetricScale.label(2_500.0, Locale.US))
        assertEquals(ScaleLabel("5,000", ScaleUnit.KILOMETERS), MetricScale.label(5_000_000.0, Locale.US))
    }

    @Test
    fun `label numbers follow the locale's decimal separator`() {
        assertEquals(ScaleLabel("2,5", ScaleUnit.KILOMETERS), MetricScale.label(2_500.0, Locale.GERMANY))
    }
}
