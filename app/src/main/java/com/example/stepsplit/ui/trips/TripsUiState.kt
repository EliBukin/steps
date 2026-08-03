package com.example.stepsplit.ui.trips

import com.example.stepsplit.domain.model.TripSummary

enum class GpsStatus { SEARCHING, WEAK, GOOD }

data class TripsUiState(
    val isLoading: Boolean = true,
    val activeTrip: TripSummary? = null,
    val interruptedTrip: TripSummary? = null,
    /** Finished trips, newest first. */
    val history: List<TripSummary> = emptyList(),
    val elapsedSeconds: Long = 0L,
    val acceptedPointCount: Int = 0,
    val gpsStatus: GpsStatus = GpsStatus.SEARCHING,
)
