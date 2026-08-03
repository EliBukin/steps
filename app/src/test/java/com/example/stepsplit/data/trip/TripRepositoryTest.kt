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
    fun `finishing an active trip marks it FINISHED with an end timestamp`() = runTest {
        val tripId = repository.startTrip()
        clock.instant = fixedNow.plusSeconds(600)
        repository.finishTrip(tripId)

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.FINISHED.name, trip.state)
        assertEquals(fixedNow.plusSeconds(600).epochSecond, trip.endEpochSecond)
    }

    @Test
    fun `finishing an already-finished trip is idempotent and does not move the end timestamp`() = runTest {
        val tripId = repository.startTrip()
        clock.instant = fixedNow.plusSeconds(600)
        repository.finishTrip(tripId)
        val firstEnd = repository.getTrip(tripId)!!.endEpochSecond

        clock.instant = fixedNow.plusSeconds(9_999) // would produce a different timestamp if reapplied
        repository.finishTrip(tripId)

        assertEquals(firstEnd, repository.getTrip(tripId)!!.endEpochSecond)
    }

    @Test
    fun `finishing a trip that was never active does nothing`() = runTest {
        repository.finishTrip(999L) // no such trip - must not throw
        assertNull(repository.getTrip(999L))
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
        repository.finishTrip(tripId)
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
        repository.finishTrip(tripId)
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
    }

    @Test
    fun `resuming an interrupted trip returns it to ACTIVE and preserves its distance and points`() = runTest {
        val tripId = repository.startTrip()
        repository.recordAcceptedBatch(tripId, listOf(sample(32.0, 34.0, fixedNow.epochSecond + 10)))
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        repository.resumeInterruptedTrip(tripId)

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.ACTIVE.name, trip.state)
        assertEquals(1, repository.getTripPoints(tripId).size)
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
        repository.finishTrip(tripId)

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
}
