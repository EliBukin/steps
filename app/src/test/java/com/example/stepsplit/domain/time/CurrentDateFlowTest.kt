package com.example.stepsplit.domain.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A [Clock] whose [instant] and [currentZone] can each be changed mid-test, standing in for real time passing and/or a live device timezone change. */
private class MutableClock(var instant: Instant, var currentZone: ZoneId) : Clock() {
    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = currentZone
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentDateFlowTest {

    @Test
    fun `emits the next day once local midnight passes while still collecting`() = runTest {
        val clock = MutableClock(Instant.parse("2026-07-28T23:59:58Z"), ZoneOffset.UTC)
        val seen = mutableListOf<LocalDate>()

        val job = launch { currentDateFlow(clock).collect { seen.add(it) } }
        testScheduler.runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 7, 28)), seen)

        // Time actually passing midnight while the collector is still alive - the app was open
        // the whole time, so nothing external re-triggers the date computation.
        clock.instant = Instant.parse("2026-07-29T00:00:05Z")
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()

        assertEquals(listOf(LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 29)), seen)
        job.cancel()
    }

    @Test
    fun `a fresh collection immediately reflects the clock's current date`() = runTest {
        val clock = MutableClock(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 7, 28), currentDateFlow(clock).first())

        // Models the app returning to the foreground the next day: WhileSubscribed tears down
        // and restarts the upstream flow, so a brand new collection must see the new date right
        // away rather than waiting for the internal midnight timer.
        clock.instant = Instant.parse("2026-07-29T09:00:00Z")
        assertEquals(LocalDate.of(2026, 7, 29), currentDateFlow(clock).first())
    }

    @Test
    fun `a fresh collection reflects a live device timezone change even without the instant moving`() = runTest {
        // 20:00 UTC: in UTC+9 that's already the next day; in UTC-9 it's still the same day.
        val instant = Instant.parse("2026-07-28T20:00:00Z")
        val clock = MutableClock(instant, ZoneOffset.ofHours(9))
        assertEquals(LocalDate.of(2026, 7, 29), currentDateFlow(clock).first())

        // Models the device's timezone changing while the app process (and this same Clock
        // instance, as used by the app-wide DeviceZoneClock) stays alive.
        clock.currentZone = ZoneOffset.ofHours(-9)
        assertEquals(LocalDate.of(2026, 7, 28), currentDateFlow(clock).first())
    }
}
