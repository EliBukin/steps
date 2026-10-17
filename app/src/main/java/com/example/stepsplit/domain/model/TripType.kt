package com.example.stepsplit.domain.model

/**
 * What kind of trip a finished trip was, so Stats can keep hikes apart from 4x4 drives: [WALK]
 * covers walks, hikes and jogs, [DRIVE] anything in a vehicle. Stored by [name], like [TripState].
 *
 * A trip's type is the owner's choice when they made one ([TripSummary.typeOverride]), otherwise
 * the automatic suggestion from its speed - see [com.example.stepsplit.domain.trip.TripTypeRule].
 */
enum class TripType { WALK, DRIVE }
