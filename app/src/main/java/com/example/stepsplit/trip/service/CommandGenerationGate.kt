package com.example.stepsplit.trip.service

import java.util.concurrent.atomic.AtomicLong

/**
 * The atomic core of command-generation safety for [TripRecordingCommandController]: a monotonic
 * generation counter plus a single primitive, [runIfCurrent], that makes "is this command still the
 * current one" and "perform this command's synchronous side effect" one indivisible operation.
 *
 * This exists because a two-step pattern - check [isCurrent], release, *then* act - has a real gap: a
 * concurrent [begin] can be accepted in between, and the stale command's action still runs afterward
 * regardless, stopping or replacing whatever the newer command just started. [runIfCurrent] closes
 * that gap by holding the same lock [begin] uses for the entire currency-check-and-act, so no
 * [begin] call can ever be accepted between them - once a newer generation is accepted, every older
 * generation's [runIfCurrent] call is permanently, atomically rejected.
 *
 * [action] must therefore be fast and strictly non-suspending (a plain lambda, not `suspend`): it
 * runs while holding [lock], and a suspending repository call must never be placed inside it (see
 * [TripRecordingCommandController]'s own doc comment for why that split matters). In practice
 * [action] is always one or two synchronous calls - [com.example.stepsplit.data.trip
 * .TripRecordingCoordinator.start]/`stop`, or the service's own stop callback - never Room access.
 *
 * ## Generation identity across instances
 *
 * [begin] draws every generation value from [ProcessWideCommandTokens] - a single counter shared by
 * *every* [CommandGenerationGate] instance in this process, not a counter private to this one. A
 * fresh [TripRecordingCommandController] (and therefore a fresh gate) is constructed for every new
 * `TripRecordingService` instance, e.g. across a stop-then-restart within the same process; if each
 * gate instead numbered its own generations from zero, two different instances could issue the exact
 * same numeric value, and [com.example.stepsplit.data.trip.TripRepository] - which uses these same
 * values as ownership tokens for its Finish-cutoff and recording-ownership state, and only compares
 * them for equality/ordering, with no notion of "which gate issued this" - could then mistake an old
 * instance's stale token for a legitimately current one belonging to a different, newer instance.
 * Sharing one counter across every instance makes that collision structurally impossible: whichever
 * instance called [begin] later always holds the numerically larger, and therefore correctly
 * "newer," value, no matter how many separate gate instances exist in this process.
 *
 * ## Terminal state
 *
 * [shutdown] adds a permanent, one-way closed state on top of the generation check: once closed,
 * *every* [runIfCurrent] call is rejected forever, even for a generation that was genuinely current a
 * moment before closing, and even for a generation issued *after* closing (see [begin] - closing does
 * not stop new generations from being issued, only from ever successfully acting). This is what lets
 * [TripRecordingCommandController.shutdown] guarantee that no command handler resuming after the
 * service has been destroyed - however far it had already suspended past its last currency check -
 * can start, stop, or replace a collector, or invoke the service's stop callback, ever again.
 */
class CommandGenerationGate(
    private val nextToken: () -> Long = ProcessWideCommandTokens::next,
) {
    private val lock = Any()
    private var latestGeneration = 0L
    private var closed = false

    /** Call synchronously, once per incoming command, before dispatching any suspend work for it. */
    fun begin(): Long = synchronized(lock) {
        latestGeneration = nextToken()
        latestGeneration
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) { !closed && generation == latestGeneration }

    /**
     * Atomically: if [generation] is still the current one *and this gate is not closed*, runs
     * [action] and returns its result; otherwise does nothing and returns `null`. See the class doc
     * comment for why this must be one atomic step rather than a separate [isCurrent] check followed
     * by an unguarded call.
     */
    fun <T> runIfCurrent(generation: Long, action: () -> T): T? = synchronized(lock) {
        if (closed || generation != latestGeneration) null else action()
    }

    /**
     * Terminal and idempotent: permanently closes this gate and, still holding [lock] - so this is
     * one atomic step with, and can never interleave with, any concurrent [begin]/[runIfCurrent] call
     * - runs [finalStop]. Mutual exclusion on [lock] means a [runIfCurrent] action already genuinely
     * in flight when this is called is not interrupted: this call simply waits for [lock] like any
     * other caller would, so that in-flight action finishes first, and only then does closing and
     * [finalStop] happen - guaranteeing the last observable state is the stopped one, never a stale
     * action winning a race against it. A second call is a safe no-op (`finalStop` does not run
     * again, and the already-closed state is simply confirmed). Returns whatever generation was
     * current at the moment of closure, so a caller can use it as an ownership threshold for its own
     * cleanup (see [TripRecordingCommandController.shutdown]).
     */
    fun shutdown(finalStop: () -> Unit): Long = synchronized(lock) {
        if (!closed) {
            closed = true
            finalStop()
        }
        latestGeneration
    }
}

/**
 * A single, process-wide monotonic counter backing every [CommandGenerationGate.begin] call by
 * default - see that class's own "Generation identity across instances" doc section for why sharing
 * one source across every gate instance in the process matters. Deliberately not exposed outside this
 * file: nothing but [CommandGenerationGate] should ever draw from it directly.
 */
private object ProcessWideCommandTokens {
    private val counter = AtomicLong(0L)
    fun next(): Long = counter.incrementAndGet()
}
