package com.example.stepsplit.data.trip

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.domain.classification.MinuteBucket
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripStepEstimate
import com.example.stepsplit.domain.model.TripSummary
import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.RouteMath
import com.example.stepsplit.domain.trip.RouteSampleDecision
import com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy
import com.example.stepsplit.domain.trip.TripStepEstimator
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every read/write path over manually recorded GPS trips - entirely separate from
 * [com.example.stepsplit.data.repository.StepRepository]: a trip never inserts or edits
 * `walk_bouts`, creates a session override, or changes daily step totals. It only ever *reads*
 * `step_buckets` (see [estimatedSteps]) to derive an honest, boundary-aware step estimate for a
 * finished trip - it never writes there either.
 *
 * A single [tripMutex] serializes start/finish/point-recording/recovery the same way
 * [com.example.stepsplit.data.repository.StepRepository.syncMutex] does for step sync, so a
 * location callback racing a Finish tap (or a duplicate service start) can never interleave writes.
 *
 * [stepSourceId] is only used to answer "has the normal step source synced through this trip's end
 * yet" for [estimatedSteps] - this repository never subscribes to or reads live data from that
 * source itself.
 */
class TripRepository(
    private val database: StepSplitDatabase,
    private val clock: Clock,
    private val stepSourceId: String,
) {
    private val tripMutex = Mutex()

    /**
     * Idempotent: if a trip is already ACTIVE, its existing id is returned and no new row is
     * created - this is what makes a duplicate Start command (a double-tap, or the OS redelivering
     * a start command) safe, and is exactly the same path an OS-triggered service restart after
     * process death uses to recover the existing trip rather than creating a duplicate.
     */
    suspend fun startTrip(): Long = tripMutex.withLock {
        database.tripDao().getByState(TripState.ACTIVE.name)?.let { return@withLock it.id }
        val now = clock.instant()
        val trip = TripEntity(
            startEpochSecond = now.epochSecond,
            endEpochSecond = null,
            startZoneId = clock.zone.id,
            state = TripState.ACTIVE.name,
            distanceMeters = 0.0,
            lastAcceptedPointEpochSecond = null,
            createdAtEpochSecond = now.epochSecond,
        )
        database.tripDao().insert(trip)
    }

    /** Idempotent: a trip that is not currently ACTIVE (already finished, interrupted, or unknown) is left untouched. */
    suspend fun finishTrip(tripId: Long) = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock
        if (trip.state != TripState.ACTIVE.name) return@withLock
        database.tripDao().update(trip.copy(state = TripState.FINISHED.name, endEpochSecond = clock.instant().epochSecond))
    }

    /**
     * Filters [samples] through [RoutePointAcceptancePolicy] (processed in chronological order
     * regardless of delivery order) and persists every accepted one, accumulating distance only
     * between consecutive *accepted* points - the first accepted point of a trip contributes zero
     * distance. Each point's insert and the trip's updated distance/timestamp commit together in
     * one transaction, so a crash between them can never leave the two inconsistent.
     *
     * Re-reads the trip's own state before processing: a batch delivered after [finishTrip] has
     * already run (a stale/delayed callback) finds the trip no longer ACTIVE and is dropped
     * entirely - it can never append a point or increase distance after Finish.
     */
    suspend fun recordAcceptedBatch(tripId: Long, samples: List<RawLocationSample>) {
        if (samples.isEmpty()) return
        tripMutex.withLock {
            val trip = database.tripDao().getById(tripId) ?: return@withLock
            if (trip.state != TripState.ACTIVE.name) return@withLock

            var lastAccepted = database.tripPointDao().getLastPoint(tripId)?.toSample()
            var distance = trip.distanceMeters
            var latestAcceptedEpochSecond = trip.lastAcceptedPointEpochSecond
            val nowEpochSecond = clock.instant().epochSecond

            for (sample in samples.sortedBy { it.capturedAtEpochSecond }) {
                val decision = RoutePointAcceptancePolicy.evaluate(sample, lastAccepted, nowEpochSecond)
                val accepted = (decision as? RouteSampleDecision.Accepted)?.sample ?: continue

                val segmentMeters = lastAccepted?.let {
                    RouteMath.haversineMeters(it.latitude, it.longitude, accepted.latitude, accepted.longitude)
                } ?: 0.0
                distance += segmentMeters
                latestAcceptedEpochSecond = accepted.capturedAtEpochSecond

                database.withTransaction {
                    database.tripPointDao().insert(accepted.toEntity(tripId))
                    database.tripDao().update(
                        trip.copy(distanceMeters = distance, lastAcceptedPointEpochSecond = latestAcceptedEpochSecond),
                    )
                }
                lastAccepted = accepted
            }
        }
    }

    /**
     * Called once per process start (see [com.example.stepsplit.ui.trips.TripsViewModel]) to
     * honestly reconcile an ACTIVE trip whose recording service cannot be confirmed running in
     * *this* process - most plausibly because the app was force-stopped, or Android chose not to
     * restart the service. [isServiceRunning] is evaluated by the caller (only it knows the
     * service's actual live state) immediately before this call; if true, the trip is left exactly
     * as it is - still genuinely recording. If false, the trip is marked [TripState.INTERRUPTED]
     * rather than silently left ACTIVE (which would let the UI keep claiming to record with nothing
     * behind it) or silently finished (which would fabricate an end time nobody actually observed).
     * The user resolves it explicitly via [resumeInterruptedTrip] or [finishInterruptedTripAtLastPoint].
     */
    suspend fun reconcileActiveTripOnLaunch(isServiceRunning: Boolean) = tripMutex.withLock {
        if (isServiceRunning) return@withLock
        val trip = database.tripDao().getByState(TripState.ACTIVE.name) ?: return@withLock
        database.tripDao().update(trip.copy(state = TripState.INTERRUPTED.name))
    }

    /** The user's choice to continue an [TripState.INTERRUPTED] trip, accepting the visible gap. Does not itself restart the recording service - the caller must also do that. */
    suspend fun resumeInterruptedTrip(tripId: Long) = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock
        if (trip.state != TripState.INTERRUPTED.name) return@withLock
        database.tripDao().update(trip.copy(state = TripState.ACTIVE.name))
    }

    /** The user's choice to end an [TripState.INTERRUPTED] trip honestly at the last point actually recorded, rather than resuming it. */
    suspend fun finishInterruptedTripAtLastPoint(tripId: Long) = tripMutex.withLock {
        val trip = database.tripDao().getById(tripId) ?: return@withLock
        if (trip.state != TripState.INTERRUPTED.name) return@withLock
        val endEpochSecond = trip.lastAcceptedPointEpochSecond ?: trip.startEpochSecond
        database.tripDao().update(trip.copy(state = TripState.FINISHED.name, endEpochSecond = endEpochSecond))
    }

    suspend fun getTrip(tripId: Long): TripEntity? = database.tripDao().getById(tripId)

    /** Used by [com.example.stepsplit.trip.service.TripRecordingService] to resolve which trip a Finish command applies to without needing it passed through the triggering Intent - only one trip can ever be ACTIVE. */
    suspend fun getActiveTripId(): Long? = database.tripDao().getByState(TripState.ACTIVE.name)?.id

    fun observeTrip(tripId: Long): Flow<TripSummary?> = database.tripDao().observeById(tripId).map { it?.toSummary() }

    fun observeTrips(): Flow<List<TripSummary>> = database.tripDao().observeAll().map { trips -> trips.map { it.toSummary() } }

    fun observeTripPoints(tripId: Long): Flow<List<TripPoint>> =
        database.tripPointDao().observeForTrip(tripId).map { points -> points.map { it.toDomain() } }

    suspend fun getTripPoints(tripId: Long): List<TripPoint> =
        database.tripPointDao().getAllForTrip(tripId).map { it.toDomain() }

    /** Cascades to every point of this trip - see [TripPointEntity]'s foreign key. */
    suspend fun deleteTrip(tripId: Long) = database.tripDao().deleteById(tripId)

    /**
     * [TripStepEstimate.Pending] while the trip is still active (nothing final to estimate yet) or
     * while the normal step source hasn't synced through the trip's end - never a guessed or
     * silently-zero value. See [TripStepEstimator] for the boundary-minute overlap math.
     */
    suspend fun estimatedSteps(trip: TripSummary): TripStepEstimate {
        val tripEnd = trip.endEpochSecond ?: return TripStepEstimate.Pending
        val latestSynced = database.stepBucketDao().latestBucketEnd(stepSourceId)
        if (latestSynced == null || latestSynced < tripEnd) return TripStepEstimate.Pending
        val buckets = database.stepBucketDao().getAllActive().map { MinuteBucket(it.startEpochSecond, it.steps) }
        val estimate = TripStepEstimator.estimateSteps(buckets, trip.startEpochSecond, tripEnd)
        return TripStepEstimate.Available(Math.round(estimate))
    }
}

private fun TripEntity.toSummary() = TripSummary(
    id = id,
    startEpochSecond = startEpochSecond,
    endEpochSecond = endEpochSecond,
    startZoneId = startZoneId,
    state = TripState.valueOf(state),
    distanceMeters = distanceMeters,
    lastAcceptedPointEpochSecond = lastAcceptedPointEpochSecond,
)

private fun TripPointEntity.toDomain() = TripPoint(
    capturedAtEpochSecond = capturedAtEpochSecond,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
)

private fun TripPointEntity.toSample() = RawLocationSample(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    capturedAtEpochSecond = capturedAtEpochSecond,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
)

private fun RawLocationSample.toEntity(tripId: Long) = TripPointEntity(
    tripId = tripId,
    capturedAtEpochSecond = capturedAtEpochSecond,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
)
