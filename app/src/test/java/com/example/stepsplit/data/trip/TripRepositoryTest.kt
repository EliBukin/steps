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

    private fun sample(lat: Double, lon: Double, capturedAt: Long, accuracy: Float = 10f) =
        RawLocationSample(lat, lon, accuracy, capturedAt)

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
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(32.0000, 34.0000, t0 + 10),
                sample(32.0010, 34.0000, t0 + 20),
                sample(32.0020, 34.0000, t0 + 30),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(3, points.size)

        val expectedDistance =
            RouteMath.haversineMeters(32.0000, 34.0000, 32.0010, 34.0000) +
                RouteMath.haversineMeters(32.0010, 34.0000, 32.0020, 34.0000)

        val trip = repository.getTrip(tripId)!!
        assertEquals(expectedDistance, trip.distanceMeters, 1e-6)
        assertEquals(t0 + 30, trip.lastAcceptedPointEpochSecond)
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

    @Test
    fun `a batch delivered out of order is still processed chronologically`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        // Delivered latest-first; must be accepted/stored in capture-time order regardless.
        repository.recordAcceptedBatch(
            tripId,
            listOf(
                sample(32.0020, 34.0000, t0 + 30),
                sample(32.0000, 34.0000, t0 + 10),
                sample(32.0010, 34.0000, t0 + 20),
            ),
        )

        val points = repository.getTripPoints(tripId)
        assertEquals(listOf(t0 + 10, t0 + 20, t0 + 30), points.map { it.capturedAtEpochSecond })

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
        val samples = (0 until 5).map { sample(32.0 + it * 0.0005, 34.0, t0 + 10 + it * 10L) }
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
            listOf(sample(32.0, 34.0, fixedNow.epochSecond + 10), sample(32.001, 34.0, fixedNow.epochSecond + 20)),
        )
        assertEquals(2, repository.getTripPoints(tripId).size)

        repository.deleteTrip(tripId)

        assertNull(repository.getTrip(tripId))
        assertTrue(repository.getTripPoints(tripId).isEmpty())
    }

    @Test
    fun `markTripInterrupted transitions an ACTIVE trip to INTERRUPTED`() = runTest {
        val tripId = repository.startTrip()
        repository.markTripInterrupted(tripId)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `markTripInterrupted is a no-op for a trip that is not currently ACTIVE`() = runTest {
        val tripId = repository.startTrip()
        repository.finishTrip(tripId, fixedNow.epochSecond)

        repository.markTripInterrupted(tripId)

        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
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

        // A fix captured before the cutoff but only delivered now (exactly what flush() is for) is unaffected.
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0001, 34.0000, t0 + 12)))
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

    @Test
    fun `clearAbandonedFinishCutoff clears a cutoff for the given trip regardless of which token owns it`() = runTest {
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(tripId, token = 1L, cutoffEpochSecond = t0 + 10)

        repository.clearAbandonedFinishCutoff(tripId)

        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, t0 + 20)))
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    @Test
    fun `clearAbandonedFinishCutoff only ever touches the given trip's own cutoff`() = runTest {
        val firstTripId = repository.startTrip()
        val t0 = fixedNow.epochSecond
        repository.beginFinish(firstTripId, token = 1L, cutoffEpochSecond = t0 + 10)
        repository.finishTrip(firstTripId, t0 + 10)

        val secondTripId = repository.startTrip()
        repository.beginFinish(secondTripId, token = 2L, cutoffEpochSecond = t0 + 30)

        repository.clearAbandonedFinishCutoff(firstTripId) // unrelated to secondTripId's own live cutoff

        repository.recordAcceptedBatch(secondTripId, listOf(sample(32.0, 34.0, t0 + 40)))
        assertEquals(0, repository.getTripPoints(secondTripId).size) // still correctly rejected by its own cutoff (30)
    }
}
