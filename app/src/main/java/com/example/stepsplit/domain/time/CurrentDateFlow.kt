package com.example.stepsplit.domain.time

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits the current local date immediately on collection, then again every time local midnight
 * passes, so a long-lived collector (e.g. a ViewModel kept alive across midnight) never needs an
 * explicit external trigger to notice the day changed. Because each collection starts by emitting
 * [LocalDate.now] fresh, re-collecting this flow (as happens whenever a `WhileSubscribed` state
 * flow restarts after the UI comes back to the foreground) also picks up the correct date
 * immediately, without waiting for the internal timer.
 */
fun currentDateFlow(clock: Clock): Flow<LocalDate> = flow {
    while (true) {
        val today = LocalDate.now(clock)
        emit(today)
        val nextMidnight = today.plusDays(1).atStartOfDay(clock.zone).toInstant()
        val delayMillis = Duration.between(clock.instant(), nextMidnight).toMillis()
        // +1ms so we wake up strictly after midnight rather than exactly on the boundary.
        delay(delayMillis.coerceAtLeast(0) + 1)
    }
}
