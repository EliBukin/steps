package com.example.stepsplit.domain.trip

import kotlin.math.abs

/**
 * Which GPS fix a spot marked during a trip is placed at - pure and JVM-testable. The waiting itself
 * is done by [com.example.stepsplit.data.trip.TripSpotMarker].
 *
 * For a spot marked at time T:
 * 1. **Candidates.** A fix can be the spot's position when it is usable ([isUsable]: real
 *    coordinates, the same check as route points, and an accuracy of [MAX_ACCURACY_METERS] or
 *    better), was captured after the trip started (Fused Location can hand over a cached fix from
 *    before Start), and was captured within [MAX_FIX_AGE_SECONDS] of T, before or after. A fix the
 *    route itself drops - standing-still wobble, say - still counts: it is a good place for a spot
 *    even when it adds nothing to the route. Of the candidates, the spot takes the one closest in
 *    time to T ([closer]).
 * 2. **The newest fix, at once, when it is close enough.** It is, unless it was captured before T
 *    and the phone, at the speed that fix reported, may have moved more than [MAX_DRIFT_METERS]
 *    since. On foot (up to 4.8 km/h) any fix from the last 30 s will do; in a jeep at 40 km/h only
 *    one from the last 3.6 s. The newest fix is often 5-25 s old: the recording asks for a fix every
 *    10 s, gets none while standing still (it asks only for fixes 8 m apart), and the phone may hand
 *    fixes over in batches up to 15 s late - a spot marked from a moving jeep would otherwise land a
 *    few hundred meters back down the track.
 * 3. **Otherwise wait up to [WAIT_FOR_FIX_MILLIS] for a closer one**: the recording's next fix, or a
 *    fresh one asked for at once - the fresh one normally takes about a second, since the GPS is
 *    already on, and it is what makes a spot work while standing still at a spring. Waiting stops
 *    at the first fix that is close enough, or that was captured after T, since no later fix can be
 *    closer in time ([needsCloserFix]). When the wait ends, the closest candidate so far is used.
 * 4. **No candidate at all: no spot**, and the owner is told there is no GPS fix yet.
 *
 * The spot keeps the fix's own time and height with its position, so the three always agree.
 */
object SpotFixRule {
    /** How far from the moment a spot was marked - before or after - its fix may have been captured. */
    const val MAX_FIX_AGE_SECONDS = 30L

    /** How far the phone may have moved since a fix for it to be used without waiting for a closer one - about GPS accuracy under trees or in a wadi. */
    const val MAX_DRIFT_METERS = 40.0

    /**
     * Looser than the route's 30 m ([RoutePointAcceptancePolicy.MAX_ACCURACY_METERS]), which guards
     * the distance against noise; a spot is one place, and a 40 m fix in a canyon is still worth
     * marking. Worse than 50 m is most likely a Wi-Fi or cell-tower estimate, not GPS.
     */
    const val MAX_ACCURACY_METERS = 50f

    /** How long marking waits for a closer fix - "a few seconds", enough for a fresh fix while the GPS is on. */
    const val WAIT_FOR_FIX_MILLIS = 10_000L

    /** Whether [fix] could be a spot's position at all, whenever it was captured. */
    fun isUsable(fix: RawLocationSample): Boolean =
        RoutePointAcceptancePolicy.isValidCoordinate(fix.latitude, fix.longitude) &&
            fix.accuracyMeters.isFinite() && fix.accuracyMeters > 0f && fix.accuracyMeters <= MAX_ACCURACY_METERS

    /** Whether [fix] may be the position of a spot marked at [markedAtEpochSecond] on a trip that started at [tripStartEpochSecond] - step 1. */
    fun isCandidate(fix: RawLocationSample, markedAtEpochSecond: Long, tripStartEpochSecond: Long): Boolean =
        isUsable(fix) &&
            fix.capturedAtEpochSecond >= tripStartEpochSecond &&
            abs(fix.capturedAtEpochSecond - markedAtEpochSecond) <= MAX_FIX_AGE_SECONDS

    /** Of [best] (a candidate, or null) and [fix], the candidate closer in time to [markedAtEpochSecond]; [best] when [fix] isn't a candidate or is no closer. */
    fun closer(best: RawLocationSample?, fix: RawLocationSample, markedAtEpochSecond: Long, tripStartEpochSecond: Long): RawLocationSample? {
        if (!isCandidate(fix, markedAtEpochSecond, tripStartEpochSecond)) return best
        if (best == null) return fix
        return if (abs(fix.capturedAtEpochSecond - markedAtEpochSecond) < abs(best.capturedAtEpochSecond - markedAtEpochSecond)) fix else best
    }

    /**
     * Whether a closer fix than [best] (a candidate, or null for none yet) is still worth waiting
     * for - steps 2 and 3: always without a candidate; never once [best] was captured at or after
     * [markedAtEpochSecond]; otherwise when the phone may have moved more than [MAX_DRIFT_METERS]
     * since [best], at the speed it reported (none counts as standing still).
     */
    fun needsCloserFix(best: RawLocationSample?, markedAtEpochSecond: Long): Boolean {
        if (best == null) return true
        val secondsBefore = markedAtEpochSecond - best.capturedAtEpochSecond
        if (secondsBefore <= 0) return false
        val speed = best.speedMetersPerSecond?.takeIf { it.isFinite() && it > 0f } ?: 0f
        return secondsBefore * speed > MAX_DRIFT_METERS
    }
}
