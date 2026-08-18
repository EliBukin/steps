package com.example.stepsplit.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TripDetailUiState(
    val isLoading: Boolean = true,
    val trip: TripSummary? = null,
    val points: List<TripPoint> = emptyList(),
    val deleted: Boolean = false,
)

/**
 * [uiState.points] and [uiState.trip]'s own `distanceMeters` are both derived from the *same*
 * `RouteSanitizer.sanitize().points -> RouteSmoother.smooth()` pipeline over the trip's raw stored
 * points - never the raw points list and never [TripSummary.distanceMeters] as persisted (which was
 * accumulated live, at accept-time, under whichever
 * [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy] was active when each point was
 * originally recorded - see that class's own doc comment for why an already-stored trip can still
 * carry outlier-inflated distance from before this policy was hardened). [RouteSanitizer] removes
 * points with concrete evidence against them; [RouteSmoother] then reduces the ordinary GPS wobble
 * that remains even among points the sanitizer had no reason to reject - see its own doc comment.
 * This two-stage pipeline is what guarantees the map polyline, camera bounds, start/finish markers,
 * GPX export, and the displayed distance in this screen can never disagree with each other: every
 * one of them reads from this single [uiState.points]/[uiState.trip] pair, computed once per
 * points-flow emission here rather than separately (or repeatedly, on every Compose recomposition)
 * downstream. The underlying stored rows themselves are never modified - this is purely a read-time,
 * display/export transform.
 */
class TripDetailViewModel(
    private val repository: TripRepository,
    private val tripId: Long,
) : ViewModel() {

    val uiState: StateFlow<TripDetailUiState> = combine(
        repository.observeTrip(tripId),
        repository.observeTripPoints(tripId),
    ) { trip, rawPoints ->
        val smoothed = RouteSmoother.smooth(RouteSanitizer.sanitize(rawPoints).points)
        TripDetailUiState(
            isLoading = false,
            trip = trip?.copy(distanceMeters = smoothed.distanceMeters),
            points = smoothed.points,
            deleted = trip == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    fun deleteTrip() {
        viewModelScope.launch { repository.deleteTrip(tripId) }
    }
}
