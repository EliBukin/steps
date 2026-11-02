package com.example.stepsplit.ui.trips

/** The first ad slot among the finished trips comes after this many. Fewer trips, no ads. */
internal const val FIRST_AD_AFTER_TRIPS = 3

/** Then one more slot after every this many trips. */
internal const val TRIPS_BETWEEN_ADS = 8

/**
 * The ad slot after the trip at [tripIndex] among [tripCount] finished trips: its number (0 for the
 * first slot, 1 for the next...), or null when no ad goes there. After the 3rd trip, then after
 * every 8 more (the 11th, the 19th, ...). None with fewer than 3 trips, so an ad is never the first
 * or the only thing under the trips' heading.
 */
internal fun adSlotAfterTrip(tripIndex: Int, tripCount: Int): Int? {
    if (tripCount < FIRST_AD_AFTER_TRIPS || tripIndex !in 0 until tripCount) return null
    val sinceFirst = tripIndex - (FIRST_AD_AFTER_TRIPS - 1)
    return if (sinceFirst >= 0 && sinceFirst % TRIPS_BETWEEN_ADS == 0) sinceFirst / TRIPS_BETWEEN_ADS else null
}
