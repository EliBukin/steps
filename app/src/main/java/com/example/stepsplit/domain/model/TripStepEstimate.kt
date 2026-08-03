package com.example.stepsplit.domain.model

/**
 * Honest result of deriving a finished trip's steps from `step_buckets` (see
 * [com.example.stepsplit.data.trip.TripRepository.estimatedSteps]). [Pending] is returned whenever
 * the normal step source hasn't synced through the trip's end yet, rather than guessing or showing
 * a false zero.
 */
sealed interface TripStepEstimate {
    data object Pending : TripStepEstimate
    data class Available(val steps: Long) : TripStepEstimate
}
