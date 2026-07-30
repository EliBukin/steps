package com.example.stepsplit.domain.time

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceZoneClockTest {

    @Test
    fun `getZone re-reads the supplier on every call instead of freezing it at construction`() {
        var currentZone: ZoneId = ZoneId.of("America/New_York")
        val clock = DeviceZoneClock(zoneSupplier = { currentZone })

        assertEquals(ZoneId.of("America/New_York"), clock.zone)

        // Models the device's timezone changing while this same Clock instance stays alive.
        currentZone = ZoneId.of("Asia/Tokyo")

        assertEquals(ZoneId.of("Asia/Tokyo"), clock.zone)
    }
}
