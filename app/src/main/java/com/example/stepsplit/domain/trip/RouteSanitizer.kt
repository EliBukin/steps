package com.example.stepsplit.domain.trip

import com.example.stepsplit.domain.model.TripPoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The result of [RouteSanitizer.sanitize]: [points] is the cleaned, chronological sequence with
 * evidenced-bad points removed - never fabricated or interpolated, always a subset of the input in
 * its original relative order (a point that starts a new stretch only marked [TripPoint.startsStretch])
 * - and [distanceMeters] is the haversine sum of consecutive [points] in that exact cleaned
 * sequence, but for the jump to a new stretch, never the original stored/live-accumulated distance. [rejections]
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
 * not merely an implementation gap. The one look-ahead both layers share is the new stretch after
 * a gap ([RoutePointAcceptancePolicy]'s "After a gap" section): live acceptance waits for the next
 * fix, this class looks at the next stored point - the same pair. The point it keeps is marked
 * [TripPoint.startsStretch], so the jump to it counts in no distance, here or downstream.
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
 *      not move, so the *next* point is compared against the same still-good anchor. Except a jump
 *      after a gap that the next point confirms ([startsNewStretch]): kept at once, as a new stretch.
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
 * 5. **Out-and-back excursions** ([removeExcursions]). One look-ahead point is not enough for a
 *    GPS excursion of two or three fixes: on a walk, a fix 76 m back, one 26 m further, then one
 *    173 m forward onto the path, 15-34 s apart - each leg under the 6 m/s walking cap and each
 *    triple within its own accuracy budget, so every check above keeps them, while the phone's own
 *    speed says 4-5 km/h throughout. They show as a spur on the map, an 18 km/h top speed and extra
 *    distance. A run of 1 to [EXCURSION_MAX_POINTS] kept points between two kept neighbours is
 *    removed when all of these hold ([isExcursion]):
 *    - **every point involved reports a speed, none above** the vehicle threshold
 *      ([RouteMovementPlausibility.VEHICLE_REPORTED_SPEED_THRESHOLD_MPS]). Without the phone's speed
 *      nothing shows the walker didn't go there, and at driving speed a hairpin bend has this shape;
 *      either way the run is kept;
 *    - **the neighbours agree**: going straight from one to the other is ordinary movement for that
 *      speed (the same support as [shouldDropPending]: at most 1.5x the reported speed plus 1 m/s);
 *    - **getting there and back is too fast**: the legs into and out of the run both imply more than
 *      [EXCURSION_LEG_SPEED_MULTIPLIER]x the reported speed plus [EXCURSION_LEG_SPEED_MARGIN_MPS] -
 *      13 km/h at a reported 4.8 km/h;
 *    - **the run really leaves the path**: a point of it lies farther from the line between the
 *      neighbours than its own accuracy plus the neighbours' - ordinary scatter stays within that.
 *    A real turnaround or detour at walking pace keeps its legs at the phone's speed and is kept; a
 *    stop is handled before this, and its neighbours never move fast enough. The reference speed is
 *    the highest any point involved reports, so a doubt keeps the run.
 * 6. [SanitizedRoute.distanceMeters] is the haversine sum of consecutive points in the *resulting*
 *    cleaned sequence only - a removed point never contributes a segment on either side of it, and
 *    neither does the jump to a [TripPoint.startsStretch] point.
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

    /** The longest run of fixes [removeExcursions] treats as one excursion. */
    private const val EXCURSION_MAX_POINTS = 3

    /**
     * How much faster than the phone's reported speed the legs into and out of an excursion must
     * be: 2x plus 1 m/s, so 13 km/h at a reported 4.8 km/h, where the excursions seen on a real walk
     * had 15-18 km/h legs. Looser than [RouteMovementPlausibility]'s 2x plus 3 m/s pair ceiling, which
     * those legs pass, and safe only because [isExcursion] also needs the run to leave the path by
     * more than the fixes' accuracy, with neighbours that agree with each other.
     */
    private const val EXCURSION_LEG_SPEED_MULTIPLIER = 2.0
    private const val EXCURSION_LEG_SPEED_MARGIN_MPS = 1.0

    private const val EARTH_RADIUS_METERS = 6_371_000.0

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
            if (!candidate.startsStretch) {
                distance += RouteMath.haversineMeters(lastKept.latitude, lastKept.longitude, candidate.latitude, candidate.longitude)
            }
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
                        if (startsNewStretch(lastKept, candidate, deduped.getOrNull(i + 1), RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)) {
                            keep(if (drivenAcross(lastKept, candidate, deduped)) candidate else candidate.copy(startsStretch = true))
                        } else {
                            rejections += Rejection(candidate, RouteSampleRejectionReason.IMPLAUSIBLE_JUMP)
                        }
                    MovementPlausibility.SPEED_CONTRADICTION ->
                        if (startsNewStretch(lastKept, candidate, deduped.getOrNull(i + 1), RouteSampleRejectionReason.SPEED_CONTRADICTION)) {
                            keep(if (drivenAcross(lastKept, candidate, deduped)) candidate else candidate.copy(startsStretch = true))
                        } else {
                            rejections += Rejection(candidate, RouteSampleRejectionReason.SPEED_CONTRADICTION)
                        }
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

        val cleaned = removeExcursions(kept, rejections)
        if (cleaned.size == kept.size) return SanitizedRoute(kept, distance, rejections)
        val cleanedDistance = cleaned.zipWithNext { from, to ->
            if (to.startsStretch) 0.0 else RouteMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        }.sum()
        return SanitizedRoute(cleaned, cleanedDistance, rejections)
    }

    /**
     * Step 5 of the class doc comment: walks [points] (already cleaned by the steps before) and drops
     * every run of 1 to [EXCURSION_MAX_POINTS] points that [isExcursion] calls an out-and-back
     * excursion between the last point kept and the point after the run. The longest such run wins:
     * a shorter one can end on a fix that is still out on the excursion, which then looks like a
     * neighbour. A longer one never swallows a good fix, since the leg out of the run must be too
     * fast too, and a walking leg isn't. The first and last points are never in a run: there is
     * nothing on their other side.
     */
    private fun removeExcursions(points: List<TripPoint>, rejections: MutableList<Rejection>): List<TripPoint> {
        if (points.size < 3) return points
        val result = mutableListOf(points.first())
        var i = 1
        while (i < points.size) {
            val before = result.last()
            val runLength = (EXCURSION_MAX_POINTS downTo 1).firstOrNull { length ->
                i + length < points.size && isExcursion(before, points.subList(i, i + length), points[i + length])
            }
            if (runLength == null) {
                result += points[i]
                i++
            } else {
                for (j in i until i + runLength) rejections += Rejection(points[j], RouteSampleRejectionReason.OUT_AND_BACK_EXCURSION)
                i += runLength
            }
        }
        return result
    }

    /** Whether [run], between [before] and [after], is an out-and-back excursion - see step 5 of the class doc comment. */
    private fun isExcursion(before: TripPoint, run: List<TripPoint>, after: TripPoint): Boolean {
        var referenceSpeed = 0.0
        for (point in listOf(before) + run + after) {
            val reported = point.speedMetersPerSecond ?: return false
            referenceSpeed = maxOf(referenceSpeed, reported.toDouble())
        }
        if (referenceSpeed > RouteMovementPlausibility.VEHICLE_REPORTED_SPEED_THRESHOLD_MPS) return false

        if (impliedSpeed(before, after) > referenceSpeed * CONTEXTUAL_SOFT_SPEED_MULTIPLIER + CONTEXTUAL_SOFT_SPEED_MARGIN_MPS) return false

        val legCeiling = referenceSpeed * EXCURSION_LEG_SPEED_MULTIPLIER + EXCURSION_LEG_SPEED_MARGIN_MPS
        if (impliedSpeed(before, run.first()) <= legCeiling || impliedSpeed(run.last(), after) <= legCeiling) return false

        val neighbourAccuracy = maxOf(before.accuracyMeters, after.accuracyMeters)
        return run.any { distanceFromSegmentMeters(it, before, after) > it.accuracyMeters + neighbourAccuracy }
    }

    private fun impliedSpeed(from: TripPoint, to: TripPoint): Double {
        val elapsedSeconds = (to.capturedAtEpochSecond - from.capturedAtEpochSecond).coerceAtLeast(1L)
        return RouteMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude) / elapsedSeconds
    }

    /** How far [point] is from the straight line between [start] and [end], in a local metre plane around [start]. */
    private fun distanceFromSegmentMeters(point: TripPoint, start: TripPoint, end: TripPoint): Double {
        val metersPerDegreeLongitude = Math.toRadians(1.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(start.latitude))
        val metersPerDegreeLatitude = Math.toRadians(1.0) * EARTH_RADIUS_METERS
        val endEast = (end.longitude - start.longitude) * metersPerDegreeLongitude
        val endNorth = (end.latitude - start.latitude) * metersPerDegreeLatitude
        val pointEast = (point.longitude - start.longitude) * metersPerDegreeLongitude
        val pointNorth = (point.latitude - start.latitude) * metersPerDegreeLatitude
        val lengthSquared = endEast * endEast + endNorth * endNorth
        val along = if (lengthSquared == 0.0) 0.0 else ((pointEast * endEast + pointNorth * endNorth) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(pointEast - along * endEast, pointNorth - along * endNorth)
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

    /**
     * Whether [candidate], rejected for [reason] against [lastKept], starts a new stretch after a
     * gap, [next] (the point stored after it) confirming it - the live policy's own rule, on the
     * same pair it waited for: see [RoutePointAcceptancePolicy]'s "After a gap" section.
     */
    private fun startsNewStretch(lastKept: TripPoint, candidate: TripPoint, next: TripPoint?, reason: RouteSampleRejectionReason): Boolean {
        if (next == null) return false
        val gapSeconds = candidate.capturedAtEpochSecond - lastKept.capturedAtEpochSecond
        if (!RoutePointAcceptancePolicy.mayStartNewStretch(reason, gapSeconds, candidate.accuracyMeters)) return false
        return RoutePointAcceptancePolicy.confirmsNewStretch(
            ordinaryMove = evaluatePair(candidate, next) == MovementPlausibility.PLAUSIBLE,
            elapsedSeconds = next.capturedAtEpochSecond - candidate.capturedAtEpochSecond,
        )
    }

    /**
     * Whether the gap from [lastKept] to [candidate], which starts a new stretch, was driven: two or
     * more of the stored points ([points]) in the [RoutePointAcceptancePolicy.VEHICLE_CONTEXT_SECONDS]
     * up to [lastKept] reported a vehicle's speed, and judged with it the jump is plausible - the live
     * policy's own rule, on the same stored points: see [RoutePointAcceptancePolicy]'s "A gap driven".
     */
    private fun drivenAcross(lastKept: TripPoint, candidate: TripPoint, points: List<TripPoint>): Boolean {
        // [points] is in time order: from the first one in the window up to [lastKept].
        val from = lastKept.capturedAtEpochSecond - RoutePointAcceptancePolicy.VEHICLE_CONTEXT_SECONDS
        var first = points.binarySearchBy(from) { it.capturedAtEpochSecond }.let { if (it < 0) -it - 1 else it }
        val recent = mutableListOf<Float?>()
        while (first < points.size && points[first].capturedAtEpochSecond <= lastKept.capturedAtEpochSecond) recent += points[first++].speedMetersPerSecond
        val driving = RoutePointAcceptancePolicy.drivingSpeed(recent) ?: return false
        return evaluatePair(lastKept.copy(speedMetersPerSecond = driving), candidate) == MovementPlausibility.PLAUSIBLE
    }

    private fun evaluatePair(from: TripPoint, to: TripPoint): MovementPlausibility {
        val elapsedSeconds = to.capturedAtEpochSecond - from.capturedAtEpochSecond
        val distanceMeters = RouteMath.haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        return RouteMovementPlausibility.evaluate(
            distanceMeters = distanceMeters,
            elapsedSeconds = elapsedSeconds,
            fromAccuracyMeters = from.accuracyMeters,
            toAccuracyMeters = to.accuracyMeters,
            fromReportedSpeedMetersPerSecond = from.speedMetersPerSecond,
            toReportedSpeedMetersPerSecond = to.speedMetersPerSecond,
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
