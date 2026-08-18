package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint

/**
 * The result of [RouteSanitizer.sanitize]: [points] is the cleaned, chronological sequence with
 * evidenced-bad points removed - never fabricated or interpolated, always a subset of the input in
 * its original relative order - and [distanceMeters] is the haversine sum of consecutive [points]
 * in that exact cleaned sequence, never the original stored/live-accumulated distance. [rejections]
 * records what was removed and why, for tests/debug builds; product UI never needs it.
 */
data class SanitizedRoute(
    val points: List<TripPoint>,
    val distanceMeters: Double,
    val rejections: List<RouteSanitizer.Rejection> = emptyList(),
)

/**
 * Pure, JVM-testable, non-destructive route-quality cleanup for an already-recorded (or currently
 * recording) sequence of trip points - the read-time counterpart to [RoutePointAcceptancePolicy]'s
 * write-time filtering. Never mutates, deletes, or reorders the caller's own stored data;
 * [sanitize] always returns a fresh result computed from its input, leaving the input list itself
 * untouched. See [RoutePointAcceptancePolicy]'s own doc comment for the A55 field-defect background
 * this exists to fix for *already-stored* trips, whose points were accepted under the previous,
 * too-permissive policy and can never be re-collected.
 *
 * ## What is genuinely shared with the live policy, and what is not
 *
 * The **point-quality thresholds** - [RoutePointAcceptancePolicy.MAX_ACCURACY_METERS] (referenced
 * directly, not duplicated - see [classifyUnusable]) and every constant in
 * [RouteMovementPlausibility] - are a single shared source of truth used by both this class and
 * [RoutePointAcceptancePolicy], so retuning one of *those* numbers can never leave the two
 * disagreeing about what an individual point or an individual A -> B pair looks like. The
 * **contextual look-ahead reconsideration** below (step 4) is *not* shared: it exists only here.
 * Live acceptance sees one incoming fix at a time and can never look ahead to a point that has not
 * arrived yet, so it cannot perform this reconsideration regardless of how its thresholds are
 * tuned - it is a structural difference in what evidence is available at write-time vs. read-time,
 * not merely an implementation gap.
 *
 * ## Algorithm
 *
 * 1. **Classify every point** ([classifyUnusable]): drop any point with an invalid coordinate, a
 *    non-finite accuracy/speed/altitude, or an accuracy outside
 *    [RoutePointAcceptancePolicy.MAX_ACCURACY_METERS] - none of these can be plotted, exported, or
 *    measured meaningfully regardless of surrounding context, and a point recorded under an older,
 *    more permissive policy is not exempt just because it predates this cleanup.
 * 2. Sort by capture time and drop exact-duplicate timestamps (keeping the first occurrence) -
 *    platform batching/redelivery can occasionally reorder or duplicate fixes.
 * 3. **Bootstrap an anchor** ([findAnchorIndex]). The very first point is not automatically
 *    trusted: this scans forward for the first *pair* of consecutive points that are mutually
 *    consistent (plausible movement, or stationary wobble - i.e. anything except an implausible
 *    jump or speed contradiction between them, see [RouteMovementPlausibility]) and starts the
 *    cleaned sequence there, dropping any earlier points as an unanchored/bad start (e.g. a stale
 *    cached fix delivered immediately on Start, before the device's own position had settled). If
 *    no such pair exists anywhere in the whole route (a degenerate case), falls back to the very
 *    first point - the forward scan below still independently re-evaluates every later point
 *    against it rather than trusting the (already known-bad) adjacent-pair check alone.
 * 4. **Forward scan with one point of look-ahead** ([resolveNextPoint]/[shouldDropPending]). From
 *    the anchor, each subsequent point is first compared only against the *last kept* point (never
 *    the previous point in raw arrival order):
 *    - [MovementPlausibility.STATIONARY_WOBBLE] / [MovementPlausibility.IMPLAUSIBLE_JUMP] /
 *      [MovementPlausibility.SPEED_CONTRADICTION] -> dropped immediately; the last-kept anchor does
 *      not move, so the *next* point is compared against the same still-good anchor.
 *    - Plausible -> **held as `pending`, not yet kept.** A point that individually clears every
 *      pairwise check can still be the middle of a moderate out-and-back excursion that neither leg
 *      alone is extreme enough to fail (e.g. a ~55m jump then a ~41m jump, ten seconds apart, each
 *      of which is under the raw speed cap and under the reported-speed contradiction ceiling on
 *      its own). Once the *next* point arrives, [shouldDropPending] reconsiders the pending point
 *      using A (the last kept point), B (pending), and C (the new point) together - not just the
 *      two pairwise checks in isolation - and only then is B either kept (becoming the new anchor)
 *      or dropped (leaving A as the anchor, so C is re-evaluated fresh against A). A point at the
 *      very end of the route with no successor to look ahead to is kept as soon as it individually
 *      passes the pairwise check - there is nothing further to reconsider it against.
 *
 *    This design implements the required three-point isolated-spike case (A -> B implausible,
 *    B -> C implausible, A -> C plausible => B alone is removed, via the immediate-rejection path,
 *    since a jump extreme enough for that case never even becomes `pending`) *and* the moderate,
 *    genuinely-contextual case (A -> B and B -> C each individually plausible, but the detour they
 *    describe is not, via the look-ahead reconsideration) *and* generalizes to any run of
 *    consecutive bad points without special-casing "exactly one bad point". Because plausibility is
 *    judged by *implied speed* (distance / elapsed time), a long sampling gap does not by itself
 *    make the next genuine point look implausible - it simply means more elapsed time is available
 *    to explain the same distance.
 * 5. [SanitizedRoute.distanceMeters] is the haversine sum of consecutive points in the *resulting*
 *    cleaned sequence only - a removed point never contributes a segment on either side of it.
 */
object RouteSanitizer {
    data class Rejection(val point: TripPoint, val reason: RouteSampleRejectionReason)

    /**
     * Minimum absolute detour, in metres, a look-ahead reconsideration ([shouldDropPending])
     * requires before even considering dropping a pending point - below this, the gap between the
     * direct A -> C distance and the via-B path is ordinary path geometry (a real but shallow turn)
     * or plain receiver noise, not evidence of a bad fix, no matter how the *ratio* looks. This is
     * an absolute floor specifically because a ratio alone cannot tell a real short hairpin apart
     * from a longer one - both can have the same shape.
     */
    private const val CONTEXTUAL_MIN_MATERIAL_DETOUR_METERS = 20.0

    /**
     * Multiplier on the summed accuracy radii of A, B, and C used to size the portion of a detour
     * that the three fixes' own combined position uncertainty could plausibly explain by itself. A
     * detour that does not clear this budget (nor [CONTEXTUAL_MIN_MATERIAL_DETOUR_METERS]) is left
     * alone - three independently noisy fixes can combine into a real-looking but purely-noise
     * round trip on the order of their own uncertainty, and this must not be mistaken for a spike.
     */
    private const val CONTEXTUAL_ACCURACY_DETOUR_FACTOR = 1.0

    /**
     * Deliberately *looser* than [RouteMovementPlausibility.REPORTED_SPEED_CONTRADICTION_MULTIPLIER]
     * / `_MARGIN_MPS`. Those hard, per-pair thresholds already gate every point before it can even
     * become `pending`, so by construction a point that reaches [shouldDropPending] has already
     * cleared them on both its A -> B and (about to be checked) B -> C legs. A tighter bar is needed
     * here specifically to recognise a moderate jump-and-return - like the confirmed 55m-out/41m-back
     * case, where each leg individually clears the hard ceiling - as an excursion neither leg's own
     * reported speed actually supports, without which this reconsideration could never trigger at
     * all.
     */
    private const val CONTEXTUAL_SOFT_SPEED_MULTIPLIER = 1.5
    private const val CONTEXTUAL_SOFT_SPEED_MARGIN_MPS = 1.0

    fun sanitize(points: List<TripPoint>): SanitizedRoute {
        if (points.isEmpty()) return SanitizedRoute(emptyList(), 0.0)

        val rejections = mutableListOf<Rejection>()
        val usable = mutableListOf<TripPoint>()
        for (point in points) {
            val reason = classifyUnusable(point)
            if (reason == null) usable += point else rejections += Rejection(point, reason)
        }
        if (usable.isEmpty()) return SanitizedRoute(emptyList(), 0.0, rejections)
        if (usable.size == 1) return SanitizedRoute(usable, 0.0, rejections)

        val deduped = dedupeChronological(usable, rejections)
        if (deduped.size == 1) return SanitizedRoute(deduped, 0.0, rejections)

        val anchorIndex = findAnchorIndex(deduped)
        for (i in 0 until anchorIndex) rejections += Rejection(deduped[i], RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)

        val kept = mutableListOf(deduped[anchorIndex])
        var distance = 0.0
        var lastKept = deduped[anchorIndex]
        var pending: TripPoint? = null

        fun keep(candidate: TripPoint) {
            kept += candidate
            distance += RouteMath.haversineMeters(lastKept.latitude, lastKept.longitude, candidate.latitude, candidate.longitude)
            lastKept = candidate
        }

        var i = anchorIndex + 1
        while (i < deduped.size) {
            val candidate = deduped[i]
            val held = pending
            if (held == null) {
                when (evaluatePair(lastKept, candidate)) {
                    MovementPlausibility.PLAUSIBLE -> pending = candidate
                    MovementPlausibility.STATIONARY_WOBBLE ->
                        rejections += Rejection(candidate, RouteSampleRejectionReason.STATIONARY_WOBBLE)
                    MovementPlausibility.IMPLAUSIBLE_JUMP ->
                        rejections += Rejection(candidate, RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)
                    MovementPlausibility.SPEED_CONTRADICTION ->
                        rejections += Rejection(candidate, RouteSampleRejectionReason.SPEED_CONTRADICTION)
                }
                i++
            } else if (shouldDropPending(lastKept, held, candidate)) {
                rejections += Rejection(held, RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)
                pending = null
                // Do not advance i: candidate is re-evaluated fresh against the unchanged anchor.
            } else {
                keep(held)
                pending = null
                // Do not advance i: candidate is re-evaluated fresh against the newly kept anchor.
            }
        }
        pending?.let { keep(it) }

        return SanitizedRoute(kept, distance, rejections)
    }

    /**
     * Reconsiders a pending point B, using the anchor A it was originally compared against and the
     * newly-arrived point C, deciding whether the A -> B -> C path is genuine (a real turn or
     * hairpin) or whether B is more likely a bad fix that a direct A -> C connection explains
     * better. Every signal the task requires is used together, not any single one in isolation:
     *
     * - A -> C must itself be plausible movement (or stationary wobble) - if it is not, there is no
     *   trustworthy fallback path to prefer over keeping B, so B is kept.
     * - The **absolute** detour size (`AB + BC - AC`) must clear both a fixed floor and a budget
     *   sized from the three points' own accuracy radii - otherwise the wiggle is ordinary path
     *   geometry or receiver noise, not a spike, regardless of ratio.
     * - Reported speed, only when present on the AB and/or BC leg: if at least one leg has a
     *   reported speed and *none* of the legs that do are actually consistent with it, that is
     *   positive evidence the excursion is not real movement. If no leg has any reported speed at
     *   all, there is no way to distinguish a real U-turn from GPS noise from geometry alone, so B
     *   is kept - ambiguous evidence must never cause a removal.
     */
    private fun shouldDropPending(a: TripPoint, b: TripPoint, c: TripPoint): Boolean {
        if (evaluatePair(a, c).let { it != MovementPlausibility.PLAUSIBLE && it != MovementPlausibility.STATIONARY_WOBBLE }) {
            return false
        }

        val abMeters = RouteMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val bcMeters = RouteMath.haversineMeters(b.latitude, b.longitude, c.latitude, c.longitude)
        val acMeters = RouteMath.haversineMeters(a.latitude, a.longitude, c.latitude, c.longitude)
        val detourMeters = (abMeters + bcMeters - acMeters).coerceAtLeast(0.0)

        val accuracyBudget: Double = (a.accuracyMeters + b.accuracyMeters + c.accuracyMeters) * CONTEXTUAL_ACCURACY_DETOUR_FACTOR
        val materialDetourFloor = maxOf(CONTEXTUAL_MIN_MATERIAL_DETOUR_METERS, accuracyBudget)
        if (detourMeters <= materialDetourFloor) return false

        val abElapsedSeconds = b.capturedAtEpochSecond - a.capturedAtEpochSecond
        val bcElapsedSeconds = c.capturedAtEpochSecond - b.capturedAtEpochSecond
        val legs = listOfNotNull(
            b.speedMetersPerSecond?.let { reported -> reported to (abMeters / abElapsedSeconds) },
            c.speedMetersPerSecond?.let { reported -> reported to (bcMeters / bcElapsedSeconds) },
        )
        if (legs.isEmpty()) return false

        val supportedLegCount = legs.count { (reported, implied) ->
            reported >= 0f && implied <= reported * CONTEXTUAL_SOFT_SPEED_MULTIPLIER + CONTEXTUAL_SOFT_SPEED_MARGIN_MPS
        }
        return supportedLegCount == 0
    }

    private fun evaluatePair(from: TripPoint, to: TripPoint): MovementPlausibility {
        val elapsedSeconds = to.capturedAtEpochSecond - from.capturedAtEpochSecond
        val distanceMeters = RouteMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        return RouteMovementPlausibility.evaluate(
            distanceMeters = distanceMeters,
            elapsedSeconds = elapsedSeconds,
            fromAccuracyMeters = from.accuracyMeters,
            toAccuracyMeters = to.accuracyMeters,
            reportedSpeedMetersPerSecond = to.speedMetersPerSecond,
        )
    }

    /**
     * `null` if usable; otherwise the specific reason, checked in the same order as
     * [RoutePointAcceptancePolicy.evaluate] so both layers classify an equivalent bad fix the same
     * way. The accuracy bound is [RoutePointAcceptancePolicy.MAX_ACCURACY_METERS] itself, not a
     * separately maintained copy of the number, so a point that would be rejected live for poor
     * accuracy is rejected here too, including for points recorded under an older, more permissive
     * policy (accuracy between the old 50m limit and the current one is exactly the case this
     * closes).
     */
    private fun classifyUnusable(point: TripPoint): RouteSampleRejectionReason? {
        if (!isValidCoordinate(point.latitude, point.longitude)) {
            return RouteSampleRejectionReason.INVALID_COORDINATES
        }
        val speedFinite = point.speedMetersPerSecond?.isFinite() ?: true
        val altitudeFinite = point.altitudeMeters?.isFinite() ?: true
        if (!point.accuracyMeters.isFinite() || !speedFinite || !altitudeFinite) {
            return RouteSampleRejectionReason.NON_FINITE_MEASUREMENT
        }
        if (point.accuracyMeters <= 0f || point.accuracyMeters > RoutePointAcceptancePolicy.MAX_ACCURACY_METERS) {
            return RouteSampleRejectionReason.POOR_ACCURACY
        }
        return null
    }

    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    private fun dedupeChronological(points: List<TripPoint>, rejections: MutableList<Rejection>): List<TripPoint> {
        val sorted = points.sortedBy { it.capturedAtEpochSecond }
        val result = mutableListOf<TripPoint>()
        for (point in sorted) {
            if (result.isNotEmpty() && result.last().capturedAtEpochSecond == point.capturedAtEpochSecond) {
                rejections += Rejection(point, RouteSampleRejectionReason.NON_MONOTONIC_OR_DUPLICATE)
                continue
            }
            result += point
        }
        return result
    }

    /** See the class doc comment's algorithm step 3. Always returns a valid index into [points]; falls back to 0 if no mutually consistent consecutive pair exists anywhere. */
    private fun findAnchorIndex(points: List<TripPoint>): Int {
        for (i in 0 until points.size - 1) {
            val verdict = evaluatePair(points[i], points[i + 1])
            if (verdict == MovementPlausibility.PLAUSIBLE || verdict == MovementPlausibility.STATIONARY_WOBBLE) return i
        }
        return 0
    }
}
