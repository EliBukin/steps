package com.example.stepsplit.ui.trips

import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Finished trips with [TripSummary.distanceMeters] replaced by the same
 * `RouteSanitizer -> RouteSmoother` output the trip's own detail screen shows - see
 * [TripDetailViewModel]'s doc comment for why a trip's distance must never disagree between screens.
 * Shared by the Trips list ([TripsViewModel]) and the Stats screen's trip totals; each owner keeps
 * its own instance and cache.
 *
 * A finished trip's points never change again ([TripRepository.recordAcceptedBatch] only records
 * into an ACTIVE trip, and FINISHED is terminal), so each trip's distance is computed once from a
 * one-shot read and cached in [distances]. Observing every finished trip's points live instead
 * re-read and re-smoothed the entire history on every GPS fix the active trip recorded: Room
 * invalidates observers per table, not per trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FinishedTripHistory(
    private val repository: TripRepository,
    /** The displayed route distance for a list of raw points - a parameter only so a test can count how often it runs. */
    private val routeDistanceMeters: (List<TripPoint>) -> Double = ::smoothedRouteDistanceMeters,
) {
    /** Smoothed distance per finished trip id. */
    private val distances = ConcurrentHashMap<Long, Double>()

    val trips: Flow<List<TripSummary>> = repository.observeTrips()
        .map { trips -> trips.filter { it.state == TripState.FINISHED } }
        .distinctUntilChanged()
        .mapLatest { finished ->
            distances.keys.retainAll(finished.map { it.id }.toSet())
            finished.map { trip ->
                val distance = distances.getOrPut(trip.id) { routeDistanceMeters(repository.getTripPoints(trip.id)) }
                trip.copy(distanceMeters = distance)
            }
        }
        .flowOn(Dispatchers.Default)
}

/** The same `RouteSanitizer -> RouteSmoother` pipeline the detail screen and GPX export use. */
internal fun smoothedRouteDistanceMeters(points: List<TripPoint>): Double =
    RouteSmoother.smooth(RouteSanitizer.sanitize(points).points).distanceMeters
