package com.example.stepsplit.data.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RawLocationSample
import com.example.stepsplit.domain.trip.RouteMath
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class MutableClock(var instant: Instant, private val zoneId: ZoneOffset = ZoneOffset.UTC) : Clock() {
    override fun getZone() = zoneId
    override fun withZone(zone: java.time.ZoneId): Clock = this
    override fun instant(): Instant = instant
}

@RunWith(RobolectricTestRunner::class)
class TripRepositoryTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var clock: MutableClock
    private lateinit var repository: TripRepository

    private val fixedNow = Instant.parse("2026-03-10T10:00:00Z")

    private fun sample(lat: Double, lon: Double, capturedAt: Long, accuracy: Float = 10f, speed: Float? = null) =
        RawLocationSample(lat, lon, accuracy, capturedAt, speedMetersPerSecond = speed)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        clock = MutableClock(fixedNow)
        repository = TripRepository(database, clock)
    }

    @Test
    fun `starting a trip when none is active creates a new ACTIVE trip`() = runTest {
        val tripId = repository.startTrip()
        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.ACTIVE.name, trip.state)
        assertEquals(fixedNow.epochSecond, trip.startEpochSecond)
        assertNull(trip.endEpochSecond)
        assertEquals(0.0, trip.distanceMeters, 1e-9)
    }

    @Test
    fun `starting a trip when one is already active is idempotent and returns the same id`() = runTest {
        val firstId = repository.startTrip()
        val secondId = repository.startTrip()
        assertEquals(firstId, secondId)
        assertEquals(1, database.tripDao().observeAll().first().size)
    }

    @Test
    fun `only one trip is ever persisted after repeated start calls`() = runTest {
        val firstId = repository.startTrip()
        repeat(2) { repository.startTrip() }
        assertEquals(1, database.tripDao().observeAll().first().size)
        assertEquals(firstId, repository.getActiveTripId())
    }

    @Test
    fun `finishing an active trip marks it FINISHED with the given end timestamp`() = runTest {
        val tripId = repository.startTrip()
        val endAt = fixedNow.plusSeconds(600).epochSecond
        repository.finishTrip(tripId, endAt)

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.FINISHED.name, trip.state)
        assertEquals(endAt, trip.endEpochSecond)
    }

    @Test
    fun `finishing an already-finished trip is idempotent and does not move the end timestamp`() = runTest {
        val tripId = repository.startTrip()
        repository.finishTrip(tripId, fixedNow.plusSeconds(600).epochSecond)
        val firstEnd = repository.getTrip(tripId)!!.endEpochSecond

        repository.finishTrip(tripId, fixedNow.plusSeconds(9_999).epochSecond) // would produce a different timestamp if reapplied

        assertEquals(firstEnd, repository.getTrip(tripId)!!.endEpochSecond)
    }

    @Test
    fun `finishing a trip that was never active does nothing`() = runTest {
        repository.finishTrip(999L, fixedNow.epochSecond) // no such trip - must not throw
        assertNull(repository.getTrip(999L))
    }

    @Test
    fun `a Finish end time that would precede the trip's own start time is clamped to the start time`() = runTest {
        val tripId = repository.startTrip() // startEpochSecond == fixedNow.epochSecond
        repository.finishTrip(tripId, fixedNow.epochSecond - 1_000) // a bogus/earlier-than-start cutoff

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.FINISHED.name, trip.state)
        assertEquals(trip.startEpochSecond, trip.endEpochSecond)
    }

    @Test
    fun `the first accepted point of a trip contributes zero distance`() = runTest {
        val tripId = repository.startTrip()
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, fixedNow.epochSecond + 10)))

        val trip = repository.getTrip(tripId)!!
        assertEquals(0.0, trip.distanceMeters, 1e-9)
        assertEquals(1, database.tripPointDao().getAllForTrip(tripId).size)
    }

    @Test
    fun `distance accumulates only between consecutive accepted points`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        // 30s gaps (not 10s): each ~111m segment implies ~3.7 m/s, safely under the plausibility
        // ceiling - this test is about distance bookkeeping, not speed, so the timing here is
        // deliberately generous rather than coupled to that threshold.
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(32.0000, 34.0000, t0 + 10),
                sample(32.0010, 34.0000, t0 + 40),
                sample(32.0020, 34.0000, t0 + 70),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(3, points.size)

        val expectedDistance =
            RouteMath.haversineMeters(32.0000, 34.0000, 32.0010, 34.0000) +
                RouteMath.haversineMeters(32.0010, 34.0000, 32.0020, 34.0000)

        val trip = repository.getTrip(tripId)!!
        assertEquals(expectedDistance, trip.distanceMeters, 1e-6)
        assertEquals(t0 + 70, trip.lastAcceptedPointEpochSecond)
    }

    @Test
    fun `a rejected point in a batch is not persisted and does not affect distance`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(32.0000, 34.0000, t0 + 10),
                // A ~500km jump in 10 seconds - clearly implausible, must be rejected.
                sample(37.0000, 34.0000, t0 + 20),
                sample(32.0001, 34.0000, t0 + 30),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(2, points.size)
        assertTrue(points.none { it.latitude == 37.0000 })

        val expectedDistance = RouteMath.haversineMeters(32.0000, 34.0000, 32.0001, 34.0000)
        assertEquals(expectedDistance, repository.getTrip(tripId)!!.distanceMeters, 1e-6)
    }

    /**
     * Ties the strengthened [RoutePointAcceptancePolicy] directly to persisted distance, using the
     * confirmed A55 field-defect's own implied/reported speed shape: a jump implying ~9 m/s (within
     * the defect's observed 8-11 m/s range) while Android itself reports ~1.5 m/s (within the
     * defect's observed 1.3-1.8 m/s range) for that exact fix. Must never be accepted or persisted,
     * and must never inflate the trip's stored distance - see [com.example.stepsplit.domain.trip.RouteSanitizerTest]
     * for the equivalent proof at the pure route-cleaning layer.
     */
    @Test
    fun `a jump matching the confirmed A55 defect pattern is rejected live and never inflates persisted distance`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(32.0000, 34.0000, t0 + 10, speed = 1.4f),
                // ~90m over 10s = 9.0 m/s implied, but Android itself reports only 1.5 m/s for
                // this exact fix - the defect's own contradiction pattern.
                sample(32.0000 + Math.toDegrees(90.0 / EARTH_RADIUS_METERS), 34.0000, t0 + 20, speed = 1.5f),
                sample(32.0000 + Math.toDegrees(14.0 / EARTH_RADIUS_METERS), 34.0000, t0 + 30, speed = 1.4f),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(2, points.size)

        val expectedGoodDistance = RouteMath.haversineMeters(
            32.0000,
            34.0000,
            32.0000 + Math.toDegrees(14.0 / EARTH_RADIUS_METERS),
            34.0000,
        )
        assertEquals(expectedGoodDistance, repository.getTrip(tripId)!!.distanceMeters, 1e-6)
    }

    @Test
    fun `a batch delivered out of order is still processed chronologically`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        // Delivered latest-first; must be accepted/stored in capture-time order regardless. 30s
        // gaps (not 10s) keep implied speed (~3.7 m/s) safely under the plausibility ceiling - this
        // test is about ordering, not speed.
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(32.0020, 34.0000, t0 + 70),
                sample(32.0000, 34.0000, t0 + 10),
                sample(32.0010, 34.0000, t0 + 40),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(listOf(t0 + 10, t0 + 40, t0 + 70), points.map { it.capturedAtEpochSecond })

        val expectedDistance =
            RouteMath.haversineMeters(32.0000, 34.0000, 32.0010, 34.0000) +
                RouteMath.haversineMeters(32.0010, 34.0000, 32.0020, 34.0000)
        assertEquals(expectedDistance, repository.getTrip(tripId)!!.distanceMeters, 1e-6)
    }

    @Test
    fun `a stale callback delivered after finish never appends points or increases distance`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 10)))
        repository.finishTrip(tripId, t0 + 10)
        val distanceAfterFinish = repository.getTrip(tripId)!!.distanceMeters
        val pointCountAfterFinish = repository.getTripPoints(tripId).size

        // A late callback for a fix captured before Finish, delivered after it.
        repository.recordAcceptedBatch(tripId, listOf(sample(32.001, 34.0, t0 + 20)))

        assertEquals(pointCountAfterFinish, repository.getTripPoints(tripId).size)
        assertEquals(distanceAfterFinish, repository.getTrip(tripId)!!.distanceMeters, 1e-9)
    }

    @Test
    fun `distance and last-accepted-point columns always stay consistent with the stored points`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        var expectedDistance = 0.0
        var previous: RawLocationSample? = null
        // 30s spacing (not 10s): each ~55m segment implies ~1.9 m/s, safely under the plausibility
        // ceiling with margin - this test is about distance/last-point bookkeeping staying
        // consistent across repeated single-sample batches, not about speed.
        val samples = (0 until 5).map { sample(32.0 + it * 0.0005, 34.0, t0 + 10 + it * 30L) }
        for (s in samples) {
            repository.recordAcceptedBatch(tripId, listOf(s))
            previous?.let { expectedDistance += RouteMath.haversineMeters(it.latitude, it.longitude, s.latitude, s.longitude) }
            previous = s

            val trip = repository.getTrip(tripId)!!
            val storedPoints = repository.getTripPoints(tripId)
            assertEquals(expectedDistance, trip.distanceMeters, 1e-6)
            assertEquals(storedPoints.last().capturedAtEpochSecond, trip.lastAcceptedPointEpochSecond)
        }
    }

    @Test
    fun `reconcileActiveTripOnLaunch leaves a genuinely running trip untouched`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = true)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `reconcileActiveTripOnLaunch marks an active trip interrupted when the service is not running`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `reconcileActiveTripOnLaunch does nothing when there is no active trip`() = runTest {
        val tripId = repository.startTrip()
        repository.finishTrip(tripId, fixedNow.epochSecond)
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `resuming an interrupted trip returns true, transitions it to ACTIVE, and preserves its distance and points`() = runTest {
        val tripId = repository.startTrip()
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, fixedNow.epochSecond + 10)))
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        val resumed = repository.resumeInterruptedTrip(tripId)

        assertTrue(resumed)
        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.ACTIVE.name, trip.state)
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    @Test
    fun `resumeInterruptedTrip returns false and changes nothing for a trip that is not INTERRUPTED`() = runTest {
        val tripId = repository.startTrip() // ACTIVE, not INTERRUPTED

        val resumed = repository.resumeInterruptedTrip(tripId)

        assertFalse(resumed)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `resumeInterruptedTrip returns false for an unknown trip id`() = runTest {
        assertFalse(repository.resumeInterruptedTrip(999L))
    }

    @Test
    fun `finishing an interrupted trip ends it at the last accepted point's timestamp`() = runTest {
        val tripId = repository.startTrip()
        val lastPointTime = fixedNow.epochSecond + 90
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, fixedNow.epochSecond + 10), sample(32.001, 34.0, lastPointTime)))
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        clock.instant = fixedNow.plusSeconds(5_000) // must be ignored - only the last point's timestamp matters here
        repository.finishInterruptedTripAtLastPoint(tripId)

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.FINISHED.name, trip.state)
        assertEquals(lastPointTime, trip.endEpochSecond)
        assertNotEquals(clock.instant.epochSecond, trip.endEpochSecond)
    }

    @Test
    fun `finishing an interrupted trip that never received a point falls back to its start time`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        repository.finishInterruptedTripAtLastPoint(tripId)

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.FINISHED.name, trip.state)
        assertEquals(trip.startEpochSecond, trip.endEpochSecond)
    }

    @Test
    fun `deleting a trip cascades to remove its points`() = runTest {
        val tripId = repository.startTrip()
        repository.recordAcceptedBatch(
            tripId,
            // ~11m over 10s = ~1.1 m/s - this test is about cascade-delete behavior, not speed.
            listOf(sample(32.0, 34.0, fixedNow.epochSecond + 10), sample(32.0001, 34.0, fixedNow.epochSecond + 20)),
        )
        assertEquals(2, repository.getTripPoints(tripId).size)

        repository.deleteTrip(tripId)

        assertNull(repository.getTrip(tripId))
        assertTrue(repository.getTripPoints(tripId).isEmpty())
    }

    @Test
    fun `markTripInterruptedIfStillOwned transitions the ACTIVE trip to INTERRUPTED when isCurrent is true and no newer recording owns it`() = runTest {
        val tripId = repository.claimTripForStart(token = 1L) { true }

        val interrupted = repository.markTripInterruptedIfStillOwned(token = 1L) { true }

        assertTrue(interrupted)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId!!)!!.state)
    }

    @Test
    fun `markTripInterruptedIfStillOwned succeeds even with no prior recording registration - the promotion-failure case`() = runTest {
        val tripId = repository.startTrip()
        // No claim call at all - mirrors handleForegroundPromotionFailure, whose own generation never
        // started a collector and therefore never registered ownership.
        val interrupted = repository.markTripInterruptedIfStillOwned(token = 1L) { true }

        assertTrue(interrupted)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `markTripInterruptedIfStillOwned is a no-op when no trip is currently ACTIVE`() = runTest {
        val tripId = repository.startTrip()
        repository.finishTrip(tripId, fixedNow.epochSecond)

        val interrupted = repository.markTripInterruptedIfStillOwned(token = 1L) { true }

        assertFalse(interrupted)
        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
    }

    /**
     * Proves the [isCurrent] half of the guard in isolation, with no gate involved at all: even
     * though token 1 still exactly owns the claimed recording-ownership state, a `false` [isCurrent]
     * alone must still block the interrupt - the "a newer command has merely been dispatched, but has
     * not yet completed a claim" gap a pure token comparison cannot see on its own (see the method's
     * own doc comment for why neither check is sufficient alone).
     */
    @Test
    fun `markTripInterruptedIfStillOwned is a no-op when isCurrent is false, even though the token still exactly owns the recording`() = runTest {
        val tripId = repository.claimTripForStart(token = 1L) { true }

        val interrupted = repository.markTripInterruptedIfStillOwned(token = 1L) { false }

        assertFalse(interrupted)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId!!)!!.state)
    }

    /**
     * The core race the token half of this method's guard exists to close: a newer collector
     * (token 2) idempotently reused the same ACTIVE trip and registered its own, newer ownership
     * *before* an older failure's (token 1) delayed interrupt attempt gets to run - see
     * [TripRepository.claimTripForStart]'s monotonic compare-and-set. [isCurrent] is passed as
     * `{ true }` throughout so this isolates the token comparison specifically: even with nothing to
     * say otherwise from the gate's own perspective, the older attempt must still be a complete no-op
     * - the trip stays ACTIVE and the newer registration is left completely intact.
     */
    @Test
    fun `markTripInterruptedIfStillOwned is a no-op once a newer token has registered as the recording owner`() = runTest {
        val tripId = repository.claimTripForStart(token = 1L) { true }
        repository.claimTripForStart(token = 2L) { true } // a newer collector has since idempotently taken over

        val interrupted = repository.markTripInterruptedIfStillOwned(token = 1L) { true }

        assertFalse(interrupted)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId!!)!!.state)

        // Token 2 can still legitimately interrupt its own, still-current ownership.
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 2L) { true })
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
    }

    /**
     * Reproduces the exact ordering that used to let a delayed OS restart create a duplicate trip:
     * 1. Create an ACTIVE trip. 2. Reconcile it to INTERRUPTED while the service is reported not
     * running (`TripsViewModel.init` running before Android's own restart arrives).
     * 3. "Deliver" the simulated null-intent restart - per the fixed `TripRecordingService.onStartCommand`,
     * a null [android.content.Intent] resolves via [TripRepository.getActiveTripId] (recover-only),
     * never via [TripRepository.startTrip] (create-if-none-active); that is the data-layer contract
     * this test proves. 4. No second trip was created, and the original trip was not silently
     * changed back.
     */
    @Test
    fun `a null-intent restart after reconciliation finds nothing to recover, never a duplicate`() = runTest {
        val tripId = repository.startTrip()

        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        val recoveredTripId = repository.getActiveTripId()

        assertNull(recoveredTripId)
        assertEquals(1, database.tripDao().observeAll().first().size)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
    }

    /** Documents the bug the fix above prevents: calling the *old* null-intent handler's `startTrip()` at this same point really would create a second, unrelated trip. */
    @Test
    fun `calling startTrip instead - the old buggy restart path - would have created a duplicate`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        val secondTripId = repository.startTrip()

        assertNotEquals(tripId, secondTripId)
        assertEquals(2, database.tripDao().observeAll().first().size)
    }

    @Test
    fun `a sample captured just before the trip's own start time is rejected`() = runTest {
        val tripId = repository.startTrip() // startEpochSecond == fixedNow.epochSecond
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, fixedNow.epochSecond - 1)))

        assertEquals(0, repository.getTripPoints(tripId).size)
        assertEquals(0.0, repository.getTrip(tripId)!!.distanceMeters, 1e-9)
        assertNull(repository.getTrip(tripId)!!.lastAcceptedPointEpochSecond)
    }

    @Test
    fun `a sample captured exactly at the trip's own start time is accepted`() = runTest {
        val tripId = repository.startTrip()
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, fixedNow.epochSecond)))

        assertEquals(1, repository.getTripPoints(tripId).size)
        assertEquals(fixedNow.epochSecond, repository.getTrip(tripId)!!.lastAcceptedPointEpochSecond)
    }

    @Test
    fun `later valid points are accepted normally alongside a rejected cached pre-Start point`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(40.0, 40.0, t0 - 3_600), // a cached fix from an hour before this trip even started
                sample(32.0000, 34.0000, t0 + 10),
                sample(32.0001, 34.0000, t0 + 20),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(2, points.size)
        assertTrue(points.none { it.latitude == 40.0 })
        val expectedDistance = RouteMath.haversineMeters(32.0000, 34.0000, 32.0001, 34.0000)
        assertEquals(expectedDistance, repository.getTrip(tripId)!!.distanceMeters, 1e-6)
    }

    @Test
    fun `a sample timestamped far in the future is rejected and cannot poison ordering for later genuine samples`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        // A bogus timestamp ~1 day ahead of "now" - if accepted, every genuine later sample would
        // look non-monotonic (elapsed <= 0) relative to it and be rejected forever after.
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 86_400)))
        assertEquals(0, repository.getTripPoints(tripId).size)

        repository.recordAcceptedBatch(tripId, listOf(sample(32.0001, 34.0000, t0 + 10)))
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    @Test
    fun `beginFinish rejects a sample captured after the Finish cutoff even while the trip is still ACTIVE`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 10)))

        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 15)

        // A fix the GPS chip happens to capture *after* Finish was requested, while the trip is
        // technically still ACTIVE during the bounded flush/grace wait - must not be appended.
        repository.recordAcceptedBatch(tripId, listOf(sample(32.001, 34.0, t0 + 20)))
        assertEquals(1, repository.getTripPoints(tripId).size)

        // A fix captured before the cutoff but only delivered now (exactly what flush() is for) is
        // unaffected. A small lat delta (~1m over 2s, well under the plausibility ceiling) keeps
        // this focused on cutoff-timing behavior rather than coupling it to the speed threshold.
        repository.recordAcceptedBatch(tripId, listOf(sample(32.00001, 34.0000, t0 + 12)))
        assertEquals(2, repository.getTripPoints(tripId).size)
    }

    @Test
    fun `beginFinish unconditionally overwrites whatever cutoff was previously outstanding for the same token`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10)
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 20) // a fresh Finish attempt for the same token

        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 15)))
        assertEquals(1, repository.getTripPoints(tripId).size) // accepted under the *new* cutoff (20), not the old one (10)
    }

    @Test
    fun `cancelFinish releases the cutoff only when the token still matches, never an already-superseded one`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10)

        // An older/unrelated token can never clear a still-active cutoff it doesn't own.
        repository.cancelFinish(token = 999L)
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 20)))
        assertEquals(0, repository.getTripPoints(tripId).size) // still rejected - the real cutoff (token 1) is untouched

        // The owning token releases it correctly.
        repository.cancelFinish(token = 1L)
        repository.recordAcceptedBatch(tripId, listOf(sample(32.001, 34.0, t0 + 20)))
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    @Test
    fun `cancelFinish for one trip's token can never clear a different, newer Finish's cutoff on the same trip`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10) // an older, now-abandoned Finish
        repository.beginFinish(tripId, token = 2L, cutoffEpochSecond = t0 + 250) // a newer Finish supersedes it, same trip

        repository.cancelFinish(token = 1L) // the old, superseded Finish's own cancellation-triggered cleanup

        // The newer Finish's cutoff (token 2) must still be in effect - proven by a sample past the
        // OLD cutoff (10) but still within the NEW one (250, and within MAX_FUTURE_SKEW_SECONDS of
        // "now") being accepted.
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 200)))
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    /**
     * The `claim*` methods' shared ownership registration replaces the previous, unowned
     * `clearAbandonedFinishCutoff(tripId)` escape hatch: a fresh claim for a trip supersedes an
     * *older* Finish cutoff still outstanding for it (see [TripRepository.claimTripForStart]'s own doc
     * comment for why "older token" is what actually proves "abandoned", not merely a trip id match).
     */
    @Test
    fun `claiming a trip for Start clears an older, abandoned Finish cutoff for the same trip`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10)

        repository.claimTripForStart(token = 2L) { true } // a newer collector taking over supersedes it

        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 20)))
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    /**
     * Trip B is interrupted first (and its own cutoff installed) *before* trip A is even created, so
     * at the moment A is claimed no other trip is ACTIVE - the claim genuinely succeeds, exercising the
     * real code path, rather than being rejected outright by the single-ACTIVE-trip guard (see
     * [claimTripForResume]'s own doc comment). [TripRepository.resumeInterruptedTrip] - the plain,
     * ownership-agnostic primitive kept for test fixtures, not the guarded `claim*` API - is then used
     * purely to inspect whether B's own cutoff survived A's claim untouched: it bypasses the
     * single-ACTIVE-trip guard (which would otherwise correctly refuse to let B become ACTIVE
     * alongside A, since this test's real interest is [recordAcceptedBatch]'s cutoff scoping, not that
     * guard) and, unlike a genuine `claim*` call, never re-supersedes B's own cutoff itself either.
     */
    @Test
    fun `claiming a trip only ever clears that trip's own cutoff, never an unrelated trip's`() = runTest {
        val t0 = fixedNow.epochSecond

        val tripB = repository.startTrip()
        repository.beginFinish(tripB, token = 1L, cutoffEpochSecond = t0 + 30) // B's own outstanding cutoff
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false) // -> INTERRUPTED
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripB)!!.state)

        val tripA = repository.startTrip() // no other trip is ACTIVE, so this claim genuinely succeeds
        repository.claimTripForStart(token = 2L) { true }

        // Directly transition B back to ACTIVE (bypassing the guarded claim* API - see doc comment
        // above) purely to inspect whether its cutoff, installed before A's claim ever ran, survived
        // that claim untouched.
        repository.resumeInterruptedTrip(tripB)

        // A point past B's own cutoff (30) is still correctly rejected - proving A's claim did not
        // clear it.
        repository.recordAcceptedBatch(tripB, listOf(sample(32.0, 34.0, t0 + 40)))
        assertEquals(0, repository.getTripPoints(tripB).size)

        // A point before B's own cutoff is still correctly accepted.
        repository.recordAcceptedBatch(tripB, listOf(sample(32.0001, 34.0000, t0 + 20)))
        assertEquals(1, repository.getTripPoints(tripB).size)
    }

    /**
     * The required regression case: a stale Start's claim reaches [TripRepository.claimTripForStart]
     * *after* a newer Finish has already installed its own cutoff for the same trip - exactly what can
     * happen when the stale Start's own suspend chain is delayed relative to the newer Finish's. No
     * recording owner has been registered yet (this is the trip's very first claim), so the claim
     * itself succeeds - but because a claim only clears a cutoff *older* than its own token, and here
     * the Finish's token (2) is newer than the stale Start's (1), the Finish's cutoff must survive
     * completely untouched regardless.
     */
    @Test
    fun `a stale Start's claim reaching the repository after a newer Finish installed its cutoff never clears it`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond

        // The newer Finish (token 2) installs its cutoff first...
        repository.beginFinish(tripId, token = 2L, cutoffEpochSecond = t0 + 50)
        // ...and only afterward does the stale Start's claim (token 1) finally run.
        repository.claimTripForStart(token = 1L) { true }

        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 60)))
        assertEquals(0, repository.getTripPoints(tripId).size) // still rejected - the newer cutoff (50) survived
    }

    @Test
    fun `claiming ownership is a monotonic compare-and-set - a stale claim can never reclaim ownership from a newer one`() = runTest {
        val tripId = repository.claimTripForStart(token = 5L) { true }

        val staleClaim = repository.claimTripForStart(token = 3L) { true } // stale - must not reclaim ownership

        assertNull(staleClaim)
        // Proven via markTripInterruptedIfStillOwned: token 3 can no longer interrupt (it never
        // regained ownership), but token 5 still legitimately can.
        assertFalse(repository.markTripInterruptedIfStillOwned(token = 3L) { true })
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId!!)!!.state)
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 5L) { true })
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
    }

    // --- Atomic trip claiming: claimTripForStart / claimTripForResume / claimActiveTripForRestart ---

    @Test
    fun `claimTripForStart creates a new ACTIVE trip and registers ownership when none is active`() = runTest {
        val tripId = repository.claimTripForStart(token = 1L) { true }

        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId!!)!!.state)
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 1L) { true }) // proves ownership was registered
    }

    @Test
    fun `claimTripForStart idempotently reuses an already-ACTIVE trip and re-registers ownership with the newer token`() = runTest {
        val firstTripId = repository.claimTripForStart(token = 1L) { true }

        val secondTripId = repository.claimTripForStart(token = 2L) { true }

        assertEquals(firstTripId, secondTripId)
        assertEquals(1, database.tripDao().observeAll().first().size)
        // Token 1 no longer owns it; token 2 does.
        assertFalse(repository.markTripInterruptedIfStillOwned(token = 1L) { true })
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 2L) { true })
    }

    @Test
    fun `claimTripForStart returns null and creates nothing when a newer token already owns recording`() = runTest {
        repository.claimTripForStart(token = 5L) { true }

        val result = repository.claimTripForStart(token = 3L) { true }

        assertNull(result)
        assertEquals(1, database.tripDao().observeAll().first().size) // no orphan trip created
    }

    /**
     * Required regression test (bug report): the monotonic token compare-and-set alone cannot detect a
     * newer generation that has merely been *reserved* by the caller's own gate but has not yet
     * completed a claim of its own - only the caller knows that, via [isCurrent]. Proves [isCurrent] is
     * evaluated atomically, before any DAO read/write: even though the token comparison alone would
     * allow this claim to proceed (no *claim* has actually registered a newer owner yet), a `false`
     * [isCurrent] must still reject it outright, touching no trip row, owner, or cutoff at all.
     */
    @Test
    fun `claimTripForStart touches no trip row when isCurrent is false, even though the token comparison alone would allow it`() = runTest {
        val result = repository.claimTripForStart(token = 1L) { false }

        assertNull(result)
        assertEquals(0, database.tripDao().observeAll().first().size)

        // Nothing was left corrupted - a subsequent, genuinely current claim still works normally.
        val secondResult = repository.claimTripForStart(token = 2L) { true }
        assertEquals(TripState.ACTIVE.name, repository.getTrip(secondResult!!)!!.state)
    }

    @Test
    fun `claimTripForResume transitions an INTERRUPTED trip to ACTIVE and registers ownership`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        val claimed = repository.claimTripForResume(tripId, token = 1L) { true }

        assertEquals(tripId, claimed)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 1L) { true }) // proves ownership was registered
    }

    @Test
    fun `claimTripForResume returns null and changes nothing for a trip that is FINISHED`() = runTest {
        val tripId = repository.startTrip()
        repository.finishTrip(tripId, fixedNow.epochSecond)

        val claimed = repository.claimTripForResume(tripId, token = 1L) { true }

        assertNull(claimed)
        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
    }

    /**
     * The convergent-takeover half of [claimTripForResume]'s contract, required by the bug report: a
     * Resume targeting a trip that is *already* ACTIVE - most plausibly because an older, racing claim
     * for that exact trip committed first while this call was delayed past its own currency check by a
     * concurrent, newer [com.example.stepsplit.trip.service.CommandGenerationGate.begin] - must still
     * succeed and take over ownership, rather than failing simply because the trip is no longer
     * INTERRUPTED. See [claimTripForResume]'s own doc comment for the full argument; this proves the
     * degenerate, no-actual-race case directly: a bare ACTIVE trip with no owner is still a legitimate
     * takeover target as long as no newer token already owns it.
     */
    @Test
    fun `claimTripForResume converges by taking over ownership when the target trip is already ACTIVE and unowned by a newer token`() = runTest {
        val tripId = repository.startTrip() // ACTIVE, no owner registered yet

        val claimed = repository.claimTripForResume(tripId, token = 1L) { true }

        assertEquals(tripId, claimed)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 1L) { true }) // proves ownership was registered
    }

    @Test
    fun `claimTripForResume's convergent takeover still respects the monotonic token compare-and-set`() = runTest {
        val tripId = repository.startTrip()
        repository.claimTripForStart(token = 5L) { true } // a newer owner already registered

        val staleTakeover = repository.claimTripForResume(tripId, token = 3L) { true } // stale - must not take over

        assertNull(staleTakeover)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertFalse(repository.markTripInterruptedIfStillOwned(token = 3L) { true }) // token 3 never gained ownership
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 5L) { true }) // token 5 still legitimately owns it
    }

    @Test
    fun `claimTripForResume returns null for an unknown trip id`() = runTest {
        assertNull(repository.claimTripForResume(999L, token = 1L) { true })
    }

    /** Required regression test (bug report) - see [claimTripForStart]'s equivalent test for the full rationale. */
    @Test
    fun `claimTripForResume touches no trip row when isCurrent is false, even though the target trip is genuinely INTERRUPTED`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        val result = repository.claimTripForResume(tripId, token = 1L) { false }

        assertNull(result)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        // Nothing was left corrupted - a subsequent, genuinely current claim still works normally.
        val secondResult = repository.claimTripForResume(tripId, token = 2L) { true }
        assertEquals(tripId, secondResult)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }

    /**
     * The single-ACTIVE-trip invariant, preserved even across a genuine cross-target supersession: a
     * claim can never take over a *different* ACTIVE trip whose own owner's token is newer or equal to
     * its own - the identical monotonic protection [claimTripForStart]'s own no-orphan-trip test proves,
     * just exercised here against a different target trip instead of the same one.
     */
    @Test
    fun `claimTripForResume cannot supersede a different ACTIVE trip whose owner's token is newer or equal`() = runTest {
        val interruptedTripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false) // -> INTERRUPTED
        val activeTripId = repository.claimTripForStart(token = 5L) { true } // genuinely owned by token 5

        val claimed = repository.claimTripForResume(interruptedTripId, token = 3L) { true } // stale: 3 < 5

        assertNull(claimed)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(interruptedTripId)!!.state)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(activeTripId!!)!!.state)
        val allTrips = database.tripDao().observeAll().first()
        assertEquals(1, allTrips.count { it.state == TripState.ACTIVE.name })
    }

    /**
     * Required regression test (bug report): the cross-target half of [TripRepository.claimTripForResume]'s
     * convergent-takeover contract. A stale claim for trip A can commit A to ACTIVE just before a
     * genuinely newer Resume for a *different*, already-INTERRUPTED trip B runs - this proves B's claim
     * must still succeed by atomically superseding A, rather than failing simply because A (not B)
     * currently occupies the single ACTIVE slot. An earlier revision rejected this outright, which left
     * A falsely ACTIVE with nothing recording it and stopped the service instead of starting B's
     * collector - see `TripRecordingCommandControllerTest`'s own controller-level reproduction of this
     * exact race for the end-to-end proof (including the live subscription count).
     */
    @Test
    fun `claimTripForResume atomically supersedes a different ACTIVE trip when its own token is genuinely newer`() = runTest {
        val interruptedTripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false) // -> INTERRUPTED
        val activeTripId = repository.claimTripForStart(token = 1L) { true } // owned by token 1

        val claimed = repository.claimTripForResume(interruptedTripId, token = 2L) { true } // newer: 2 > 1

        assertEquals(interruptedTripId, claimed)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(interruptedTripId)!!.state)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(activeTripId!!)!!.state)
        val allTrips = database.tripDao().observeAll().first()
        assertEquals(1, allTrips.count { it.state == TripState.ACTIVE.name })
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 2L) { true }) // proves ownership was registered
    }

    @Test
    fun `claimActiveTripForRestart returns the existing ACTIVE trip's id and registers ownership, never creating or resuming`() = runTest {
        val tripId = repository.startTrip()

        val claimed = repository.claimActiveTripForRestart(token = 1L) { true }

        assertEquals(tripId, claimed)
        assertEquals(1, database.tripDao().observeAll().first().size) // no new trip created
        assertTrue(repository.markTripInterruptedIfStillOwned(token = 1L) { true }) // proves ownership was registered
    }

    @Test
    fun `claimActiveTripForRestart returns null when no trip is ACTIVE, never creating or resuming one`() = runTest {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false) // -> INTERRUPTED

        val claimed = repository.claimActiveTripForRestart(token = 1L) { true }

        assertNull(claimed)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
        assertEquals(1, database.tripDao().observeAll().first().size)
    }

    /** Required regression test (bug report) - see [claimTripForStart]'s equivalent test for the full rationale. */
    @Test
    fun `claimActiveTripForRestart returns null when isCurrent is false, even though a trip is genuinely ACTIVE`() = runTest {
        val tripId = repository.startTrip()

        val result = repository.claimActiveTripForRestart(token = 1L) { false }

        assertNull(result)

        // Nothing was left corrupted - a subsequent, genuinely current claim still works normally.
        val secondResult = repository.claimActiveTripForRestart(token = 2L) { true }
        assertEquals(tripId, secondResult)
    }

    @Test
    fun `finishTripIfOwner finishes the trip only when token still owns the outstanding Finish cutoff`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10)

        val finished = repository.finishTripIfOwner(tripId, t0 + 10, token = 1L)

        assertTrue(finished)
        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
        assertEquals(t0 + 10, repository.getTrip(tripId)!!.endEpochSecond)
    }

    /**
     * The `handleFinish` race this method exists to close: a newer Finish (token 2) has installed its
     * own cutoff for the same trip - superseding the older one (token 1) - before the older Finish's
     * own bounded flush/grace wait finishes and it attempts to commit. The older attempt must be a
     * complete no-op; only the trip's *own current* Finish owner can actually transition it.
     */
    @Test
    fun `finishTripIfOwner is a no-op once a newer Finish has superseded the token's own cutoff`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10)
        repository.beginFinish(tripId, token = 2L, cutoffEpochSecond = t0 + 90) // a newer Finish supersedes it

        val finished = repository.finishTripIfOwner(tripId, t0 + 10, token = 1L)

        assertFalse(finished)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `finishTripIfOwner is a no-op when no Finish cutoff was ever begun for that token`() = runTest {
        val tripId = repository.startTrip()

        val finished = repository.finishTripIfOwner(tripId, fixedNow.epochSecond, token = 1L)

        assertFalse(finished)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }
}
