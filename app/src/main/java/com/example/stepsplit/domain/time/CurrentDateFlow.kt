package com.example.stepsplit.domain.time

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * How often a still-active collection re-checks the clock's zone/date while waiting for the next
 * scheduled midnight, instead of sleeping for the entire remaining duration in one uninterruptible
 * `delay()`. [DeviceZoneClock] re-reads the device's live default zone on every access rather than
 * freezing it, so a timezone change (flight landing, manual change) can move local midnight earlier
 * or later than what was computed under the OLD zone, or even mean "today" changes with no instant
 * passing at all - a single long `delay()` all the way to the old midnight would miss that until it
 * fires, possibly hours late. Bounding each wait to this interval instead means such a change is
 * always noticed within one interval, without resorting to per-second polling. Internal (not
 * private) so a test can advance exactly this far without duplicating the constant.
 */
internal val ZONE_CHECK_INTERVAL: Duration = Duration.ofMinutes(1)

/**
 * Emits the current local date immediately on collection, then again every time local midnight
 * passes OR the clock's zone changes while still collecting (see [ZONE_CHECK_INTERVAL]), so a
 * long-lived collector (e.g. a ViewModel kept alive across midnight, or across a live device
 * timezone change) never needs an explicit external trigger to notice the day changed. Because
 * each collection starts by emitting [LocalDate.now] fresh, re-collecting this flow (as happens
 * whenever a `WhileSubscribed` state flow restarts after the UI comes back to the foreground) also
 * picks up the correct date immediately, without waiting for the internal timer.
 */
fun currentDateFlow(clock: Clock): Flow<LocalDate> = flow {
    var current = LocalDate.now(clock)
    emit(current)
    while (true) {
        val nextMidnight = current.plusDays(1).atStartOfDay(clock.zone).toInstant()
        // +1ms so a wake-up scheduled for exactly this delay lands strictly after midnight rather
        // than exactly on the boundary.
        val delayMillis = Duration.between(clock.instant(), nextMidnight).toMillis().coerceAtLeast(0) + 1
        delay(delayMillis.coerceAtMost(ZONE_CHECK_INTERVAL.toMillis()))

        val today = LocalDate.now(clock)
        if (today != current) {
            current = today
            emit(current)
        }
    }
}
