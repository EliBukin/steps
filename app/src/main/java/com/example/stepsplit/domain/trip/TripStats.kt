package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripSummary

/**
 * Lifetime totals over finished GPS trips, for the Stats screen. [averageSpeedKmh] and
 * [averagePaceSecondsPerKm] are null when there is nothing to divide by (no time or no distance yet).
 * [tripCount] includes the [deletedTripCount] deleted trips that were kept in Stats.
 */
data class TripTotals(
    val tripCount: Int,
    val totalDurationSeconds: Long,
    val totalDistanceMeters: Double,
    val averageSpeedKmh: Double?,
    val averagePaceSecondsPerKm: Long?,
    val deletedTripCount: Int = 0,
) {
    val hasTrips: Boolean get() = tripCount > 0

    companion object {
        val EMPTY = TripTotals(0, 0L, 0.0, null, null)
    }
}

/**
 * The time and displayed distance of a finished trip that was deleted while "Keep deleted trips in
 * Stats" was on - all that is kept of it. See
 * [com.example.stepsplit.data.local.trip.DeletedTripTotalsEntity].
 */
data class DeletedTripTotals(val durationSeconds: Long, val distanceMeters: Double)

/** A finished trip's duration as the Stats screen counts it: start to end, pauses included, never negative. */
fun finishedTripDurationSeconds(startEpochSecond: Long, endEpochSecond: Long?): Long =
    ((endEpochSecond ?: startEpochSecond) - startEpochSecond).coerceAtLeast(0L)

/**
 * Pure, Android-free totals over finished trips. Uses each trip's [TripSummary.distanceMeters]
 * exactly as given, so a caller passing the cleaned-up and smoothed distances the Trips list shows
 * gets totals that match it. Averages are overall (total distance over total time), so a long trip
 * weighs more than a short one, and a trip's duration includes any pauses along the way.
 *
 * [deletedTrips] count exactly like finished trips; pass an empty list to leave them out.
 */
object TripStatsCalculator {

    fun calculate(finishedTrips: List<TripSummary>, deletedTrips: List<DeletedTripTotals> = emptyList()): TripTotals {
        if (finishedTrips.isEmpty() && deletedTrips.isEmpty()) return TripTotals.EMPTY
        val totalSeconds = finishedTrips.sumOf { finishedTripDurationSeconds(it.startEpochSecond, it.endEpochSecond) } +
            deletedTrips.sumOf { it.durationSeconds.coerceAtLeast(0L) }
        val totalMeters = finishedTrips.sumOf { it.distanceMeters.coerceAtLeast(0.0) } +
            deletedTrips.sumOf { it.distanceMeters.coerceAtLeast(0.0) }
        val kilometers = totalMeters / 1000.0
        val hours = totalSeconds / 3600.0
        return TripTotals(
            tripCount = finishedTrips.size + deletedTrips.size,
            totalDurationSeconds = totalSeconds,
            totalDistanceMeters = totalMeters,
            averageSpeedKmh = if (totalSeconds > 0 && totalMeters > 0.0) kilometers / hours else null,
            averagePaceSecondsPerKm = if (totalSeconds > 0 && totalMeters > 0.0) Math.round(totalSeconds / kilometers) else null,
            deletedTripCount = deletedTrips.size,
        )
    }
}
