package com.example.stepsplit.ui.trips

import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.model.TripType
import com.example.stepsplit.domain.trip.RoutePoint
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import com.example.stepsplit.domain.trip.RouteTraceGeometry
import com.example.stepsplit.domain.trip.SmoothedRoute
import com.example.stepsplit.domain.trip.TripTypeRule
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/** How many points a trip-list thumbnail keeps - see [RouteTraceGeometry.downsample]. */
internal const val ROUTE_PREVIEW_MAX_POINTS = 64

/**
 * A finished trip as the Trips list shows it: [summary] with its displayed distance, and
 * [routePreview], a thinned-out copy of the same displayed route for the card's thumbnail.
 * [suggestedType] is [TripTypeRule.suggest] on that same displayed route; [type] is what the trip
 * counts as - the owner's choice when they made one, otherwise the suggestion.
 */
data class FinishedTrip(
    val summary: TripSummary,
    val routePreview: List<RoutePoint>,
    val suggestedType: TripType = TripType.WALK,
) {
    val type: TripType get() = summary.typeOverride ?: suggestedType
}

/**
 * Finished trips with [TripSummary.distanceMeters] replaced by the same
 * `RouteSanitizer -> RouteSmoother` output the trip's own detail screen shows - see
 * [TripDetailViewModel]'s doc comment for why a trip's distance must never disagree between screens.
 * The thumbnail in [finishedTrips] is drawn from that same smoothed route, and the suggested
 * Walk/Drive type is worked out from it too. Shared by the Trips list ([TripsViewModel]) and the
 * Stats screen's walk and drive totals; each owner keeps its own instance and cache.
 *
 * This is also why the suggested type is never stored: the one place that needs every trip's type
 * at once (Stats) already reads and smooths every finished route here, off the main thread, for the
 * displayed distance. Classifying that same route is one more pass over points already in memory -
 * no extra read - and a later change to [TripTypeRule] applies to old trips too.
 *
 * A finished trip's points never change again ([TripRepository.recordAcceptedBatch] only records
 * into an ACTIVE trip, and FINISHED is terminal), so each trip's route is processed once from a
 * one-shot read and cached in [routes]. Observing every finished trip's points live instead
 * re-read and re-smoothed the entire history on every GPS fix the active trip recorded: Room
 * invalidates observers per table, not per trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FinishedTripHistory(
    private val repository: TripRepository,
    /** The displayed route for a list of raw points - a parameter only so a test can count how often it runs. */
    private val smoothRoute: (List<TripPoint>) -> SmoothedRoute = ::smoothedRoute,
) {
    private class CachedRoute(val distanceMeters: Double, val preview: List<RoutePoint>, val suggestedType: TripType)

    /** Displayed distance, thumbnail and suggested type per finished trip id. */
    private val routes = ConcurrentHashMap<Long, CachedRoute>()

    val finishedTrips: Flow<List<FinishedTrip>> = repository.observeTrips()
        .map { trips -> trips.filter { it.state == TripState.FINISHED } }
        .distinctUntilChanged()
        .mapLatest { finished ->
            routes.keys.retainAll(finished.map { it.id }.toSet())
            finished.map { trip ->
                val route = routes.getOrPut(trip.id) {
                    val smoothed = smoothRoute(repository.getTripPoints(trip.id))
                    CachedRoute(
                        distanceMeters = smoothed.distanceMeters,
                        preview = RouteTraceGeometry.downsample(
                            smoothed.points.map { RoutePoint(latitude = it.latitude, longitude = it.longitude) },
                            ROUTE_PREVIEW_MAX_POINTS,
                        ),
                        suggestedType = TripTypeRule.suggest(smoothed.points),
                    )
                }
                FinishedTrip(
                    summary = trip.copy(distanceMeters = route.distanceMeters),
                    routePreview = route.preview,
                    suggestedType = route.suggestedType,
                )
            }
        }
        .flowOn(Dispatchers.Default)
}

/** The same `RouteSanitizer -> RouteSmoother` pipeline the detail screen and GPX export use. */
internal fun smoothedRoute(points: List<TripPoint>): SmoothedRoute = RouteSmoother.smooth(RouteSanitizer.sanitize(points).points)
