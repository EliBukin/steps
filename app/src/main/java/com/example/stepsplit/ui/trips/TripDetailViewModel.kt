package com.example.stepsplit.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripStepEstimate
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.trip.GpxFormatter
import com.example.stepsplit.domain.trip.GpxPoint
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TripDetailUiState(
    val isLoading: Boolean = true,
    val trip: TripSummary? = null,
    val points: List<TripPoint> = emptyList(),
    val estimatedSteps: TripStepEstimate = TripStepEstimate.Pending,
    val deleted: Boolean = false,
)

class TripDetailViewModel(
    private val repository: TripRepository,
    private val tripId: Long,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TripDetailUiState> = combine(
        repository.observeTrip(tripId),
        repository.observeTripPoints(tripId),
    ) { trip, points -> trip to points }
        .mapLatest { (trip, points) ->
            TripDetailUiState(
                isLoading = false,
                trip = trip,
                points = points,
                estimatedSteps = trip?.let { repository.estimatedSteps(it) } ?: TripStepEstimate.Pending,
                deleted = trip == null,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    fun deleteTrip() {
        viewModelScope.launch { repository.deleteTrip(tripId) }
    }

    /** Builds the export content fresh from the repository rather than the (possibly stale) current [uiState], since a user could export moments after a background sync updates something upstream. */
    suspend fun buildGpxContent(fileBaseName: String): String {
        val points = repository.getTripPoints(tripId).map {
            GpxPoint(
                latitude = it.latitude,
                longitude = it.longitude,
                elevationMeters = it.altitudeMeters,
                timeEpochSecond = it.capturedAtEpochSecond,
            )
        }
        return GpxFormatter.format(fileBaseName, points)
    }
}

/** "trip_2026-08-03" - the trip's own [TripSummary.startZoneId], not the device's current zone, so the date always matches what the trip card/detail screen itself shows. */
fun tripExportFileBaseName(trip: TripSummary): String {
    val date = Instant.ofEpochSecond(trip.startEpochSecond).atZone(ZoneId.of(trip.startZoneId)).toLocalDate()
    return "trip_$date"
}
