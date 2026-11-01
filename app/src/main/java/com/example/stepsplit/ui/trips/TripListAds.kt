package com.example.stepsplit.ui.trips

/** The ad card's first place among the finished trips: after this many. Fewer trips, no card. */
internal const val FIRST_AD_AFTER_TRIPS = 3

/** Then one more card after every this many trips. */
internal const val TRIPS_BETWEEN_ADS = 8

/**
 * Where the ad card goes among [tripCount] finished trips: the indexes of the trips it follows.
 * After the 3rd trip, then after every 8 more (the 11th, the 19th, ...). None with fewer than 3
 * trips, so the card is never the first or the only thing under the trips' heading.
 */
internal fun adSlotsAfterTrips(tripCount: Int): Set<Int> =
    (FIRST_AD_AFTER_TRIPS - 1 until tripCount step TRIPS_BETWEEN_ADS).toSet()
