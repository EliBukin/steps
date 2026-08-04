package com.example.stepsplit.trip.service

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
 */
class CommandGenerationGate {
    private val lock = Any()
    private var latestGeneration = 0L

    /** Call synchronously, once per incoming command, before dispatching any suspend work for it. */
    fun begin(): Long = synchronized(lock) {
        latestGeneration += 1
        latestGeneration
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) { generation == latestGeneration }

    /**
     * Atomically: if [generation] is still the current one, runs [action] and returns its result;
     * otherwise does nothing and returns `null`. See the class doc comment for why this must be one
     * atomic step rather than a separate [isCurrent] check followed by an unguarded call.
     */
    fun <T> runIfCurrent(generation: Long, action: () -> T): T? = synchronized(lock) {
        if (generation != latestGeneration) null else action()
    }
}
