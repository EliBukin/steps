package com.example.stepsplit.ui.trips

import org.junit.Assert.assertEquals
import org.junit.Test

class TripDetailsPanelDragTest {

    private val threshold = 60f

    @Test
    fun `a drag up far enough opens the panel, from either state`() {
        assertEquals(true, panelExpandedAfterDrag(expanded = false, dragPx = -60f, thresholdPx = threshold))
        assertEquals(true, panelExpandedAfterDrag(expanded = true, dragPx = -200f, thresholdPx = threshold))
    }

    @Test
    fun `a drag down far enough closes the panel, from either state`() {
        assertEquals(false, panelExpandedAfterDrag(expanded = true, dragPx = 60f, thresholdPx = threshold))
        assertEquals(false, panelExpandedAfterDrag(expanded = false, dragPx = 300f, thresholdPx = threshold))
    }

    @Test
    fun `a short drag either way leaves the panel as it was`() {
        listOf(true, false).forEach { expanded ->
            listOf(-59f, 0f, 59f).forEach { drag ->
                assertEquals(expanded, panelExpandedAfterDrag(expanded, drag, threshold))
            }
        }
    }
}
