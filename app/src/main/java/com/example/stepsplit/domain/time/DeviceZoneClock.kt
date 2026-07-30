package com.example.stepsplit.domain.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A [Clock] that re-reads the device's default timezone on every access instead of freezing it
 * at construction time, unlike [Clock.systemDefaultZone] which captures a fixed [ZoneId] once.
 * Since [AppContainer][com.example.stepsplit.di.AppContainer] builds one clock for the whole
 * process lifetime, a frozen zone would mean a device timezone change is silently ignored until
 * the process restarts. [zoneSupplier] defaults to the live system default and is only
 * overridden in tests.
 */
class DeviceZoneClock(
    private val zoneSupplier: () -> ZoneId = ZoneId::systemDefault,
) : Clock() {
    override fun instant(): Instant = Instant.now()
    override fun getZone(): ZoneId = zoneSupplier()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
}
