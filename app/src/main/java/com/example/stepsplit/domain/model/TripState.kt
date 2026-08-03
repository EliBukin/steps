package com.example.stepsplit.domain.model

/**
 * A manually recorded GPS trip's durable state, persisted on [com.example.stepsplit.data.local.trip.TripEntity.state]
 * so it survives process death - see [com.example.stepsplit.data.trip.TripRepository].
 *
 * [ACTIVE]: currently being recorded (or believed to be - see [INTERRUPTED]).
 * [FINISHED]: ended normally via an explicit Finish action, or via interrupted-trip recovery
 * choosing to finish at the last accepted point.
 * [INTERRUPTED]: was [ACTIVE], but on a later app launch the recording service could not be
 * confirmed running - most likely a force-stop or a restart Android chose not to honor. Never
 * silently resumed or finished automatically; the user chooses how to resolve it.
 */
enum class TripState { ACTIVE, FINISHED, INTERRUPTED }
