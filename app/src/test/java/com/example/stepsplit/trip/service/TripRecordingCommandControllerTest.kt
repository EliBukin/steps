package com.example.stepsplit.trip.service

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.trip.FakeTripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RawLocationSample
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [TripRecordingCommandController] directly - the real class
 * [TripRecordingService] delegates every command to, not a copy or a re-implementation - against a
 * real in-memory [TripRepository]/Room database and [FakeTripLocationClient]. No
 * [android.app.Service] or Robolectric service shadow is involved; Robolectric here is only for the
 * Room database, the same as every other repository-level test in this suite. This is deliberately
 * the primary regression coverage for the generation-safety/Resume-atomicity races described on
 * [TripRecordingCommandController]'s own doc comment: those races are about command *ordering*, not
 * genuine Android lifecycle behavior, so they are fully and deterministically reproducible here.
 *
 * Uses [runBlocking] with a real (non-test-scheduler) [coordinatorScope] for the same reason
 * `TripRecordingCoordinatorTest` does: the coordinator's collecting coroutine and Room's own
 * background executor are both real, externally-driven work a virtual-time `TestDispatcher` cannot
 * observe completing.
 */
@RunWith(RobolectricTestRunner::class)
class TripRecordingCommandControllerTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var repository: TripRepository
    private lateinit var locationClient: FakeTripLocationClient
    private lateinit var coordinator: TripRecordingCoordinator
    private lateinit var coordinatorScope: CoroutineScope
    private lateinit var controller: TripRecordingCommandController
    private val stoppedStartIds = mutableListOf<Int>()
    private val fixedNow = Instant.parse("2026-03-10T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        repository = TripRepository(database, clock)
        locationClient = FakeTripLocationClient()
        coordinatorScope = CoroutineScope(Dispatchers.Default + Job())
        coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)
        controller = buildController(repository, coordinator, locationClient)
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
    }

    private fun buildController(
        repository: TripRepository,
        coordinator: TripRecordingCoordinator,
        locationClient: FakeTripLocationClient,
    ) = TripRecordingCommandController(
        repository = repository,
        coordinator = coordinator,
        locationClient = locationClient,
        clock = clock,
        onStopRequested = { startId -> stoppedStartIds.add(startId) },
    )

    private suspend fun awaitSubscriptionCount(client: FakeTripLocationClient, expected: Int, timeoutMs: Long = 2_000) {
        withTimeout(timeoutMs) { while (client.activeSubscriptionCount != expected) yield() }
    }

    private suspend fun awaitSubscriptionCount(expected: Int, timeoutMs: Long = 2_000) =
        awaitSubscriptionCount(locationClient, expected, timeoutMs)

    private suspend fun awaitTripState(tripId: Long, expected: TripState, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) { repository.observeTrip(tripId).first { it?.state == expected } }
    }

    // 1. Explicit Start with no active trip creates exactly one trip and starts one collector.
    @Test
    fun `explicit Start with no active trip creates exactly one trip and starts one collector`() = runBlocking {
        val gen = controller.beginCommand()
        controller.handleStart(gen, startId = 1)
        awaitSubscriptionCount(1)

        assertEquals(1, database.tripDao().observeAll().first().size)
        assertEquals(repository.getActiveTripId(), database.tripDao().observeAll().first().first().id)
    }

    // 2. Duplicate Start remains idempotent.
    @Test
    fun `duplicate Start remains idempotent`() = runBlocking {
        val gen1 = controller.beginCommand()
        controller.handleStart(gen1, startId = 1)
        awaitSubscriptionCount(1)
        val firstTripId = repository.getActiveTripId()

        val gen2 = controller.beginCommand()
        controller.handleStart(gen2, startId = 2)
        awaitSubscriptionCount(1)

        assertEquals(firstTripId, repository.getActiveTripId())
        assertEquals(1, database.tripDao().observeAll().first().size)
    }

    // 3. Null-intent restart with an existing ACTIVE trip recovers that same trip.
    @Test
    fun `null-intent restart with an existing ACTIVE trip recovers that same trip`() = runBlocking {
        val tripId = repository.startTrip()

        val gen = controller.beginCommand()
        controller.handleRestart(gen, startId = 1)
        awaitSubscriptionCount(1)

        assertEquals(tripId, repository.getActiveTripId())
        assertEquals(1, database.tripDao().observeAll().first().size)
    }

    // 4. Null-intent restart after reconciliation to INTERRUPTED creates nothing and changes nothing.
    @Test
    fun `null-intent restart after reconciliation to INTERRUPTED creates nothing and changes nothing`() = runBlocking {
        val tripId = repository.startTrip()
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        val gen = controller.beginCommand()
        controller.handleRestart(gen, startId = 1)

        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
        assertEquals(1, database.tripDao().observeAll().first().size)
        assertEquals(0, locationClient.activeSubscriptionCount)
        assertEquals(listOf(1), stoppedStartIds)
    }

    // 5. Resume uses the requested interrupted trip and preserves its route and distance.
    @Test
    fun `Resume uses the requested interrupted trip and preserves its route and distance`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, fixedNow.epochSecond + 10)))
        withTimeout(5_000) { database.tripPointDao().observeForTrip(tripId).first { it.isNotEmpty() } }
        locationClient.emit(listOf(RawLocationSample(32.001, 34.0, 10f, fixedNow.epochSecond + 20)))
        withTimeout(5_000) { repository.observeTrip(tripId).first { (it?.distanceMeters ?: 0.0) > 0.0 } }

        val distanceBeforeInterruption = repository.getTrip(tripId)!!.distanceMeters
        val pointsBeforeInterruption = repository.getTripPoints(tripId).size

        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        val resumeGen = controller.beginCommand()
        controller.handleResume(tripId, resumeGen, startId = 2)
        awaitSubscriptionCount(1)

        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertEquals(distanceBeforeInterruption, repository.getTrip(tripId)!!.distanceMeters, 1e-9)
        assertEquals(pointsBeforeInterruption, repository.getTripPoints(tripId).size)
    }

    // 6. Resume never creates a second trip.
    @Test
    fun `Resume never creates a second trip`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)

        val resumeGen = controller.beginCommand()
        controller.handleResume(tripId, resumeGen, startId = 2)
        awaitSubscriptionCount(1)

        assertEquals(1, database.tripDao().observeAll().first().size)
        assertEquals(tripId, repository.getActiveTripId())
    }

    // 7a. Failed Resume (target was never interrupted) leaves the trip exactly as it was.
    @Test
    fun `resuming a trip that is not actually INTERRUPTED is a safe no-op`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!
        repository.finishTrip(tripId)

        val resumeGen = controller.beginCommand()
        controller.handleResume(tripId, resumeGen, startId = 2)

        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
        assertEquals(0, locationClient.activeSubscriptionCount)
        assertEquals(listOf(2), stoppedStartIds)
    }

    // 7b. Failed Resume (registration itself fails) leaves the trip INTERRUPTED, not stuck ACTIVE with no collector.
    @Test
    fun `a Resume whose registration fails immediately leaves the trip INTERRUPTED, not stuck ACTIVE`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        val failingClient = FakeTripLocationClient(registrationFailure = SecurityException("permission revoked"))
        val failingCoordinator = TripRecordingCoordinator(repository, failingClient, coordinatorScope)
        val failingController = buildController(repository, failingCoordinator, failingClient)

        val resumeGen = failingController.beginCommand()
        failingController.handleResume(tripId, resumeGen, startId = 2)
        awaitTripState(tripId, TripState.INTERRUPTED)

        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
        assertEquals(listOf(2), stoppedStartIds)
    }

    // Defensive: a malformed/missing EXTRA_TRIP_ID (-1) must never create or touch any trip - it safely stops instead.
    @Test
    fun `Resume with a missing trip id extra creates nothing and safely stops`() = runBlocking {
        val gen = controller.beginCommand()
        controller.handleResume(-1L, gen, startId = 1)

        assertEquals(0, database.tripDao().observeAll().first().size)
        assertEquals(listOf(1), stoppedStartIds)
    }

    // 8. A delayed old Finish followed by a new Start cannot stop the new collector/service.
    @Test
    fun `a delayed old Finish followed by a new Start cannot stop the new collector or service`() = runBlocking {
        val startGen1 = controller.beginCommand()
        controller.handleStart(startGen1, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        // The Finish command is dispatched (its generation reserved)...
        val finishGen = controller.beginCommand()
        // ...but before its (delayed) handler body actually runs, a brand-new Start supersedes it.
        val startGen2 = controller.beginCommand()
        controller.handleStart(startGen2, startId = 2)
        awaitSubscriptionCount(1)
        assertEquals(tripId, repository.getActiveTripId())

        // The old, now-stale Finish handler finally runs.
        controller.handleFinish(finishGen, startId = 1)

        // A complete no-op: the trip is still ACTIVE, its collector is still running, and the
        // service was never asked to stop on the stale command's behalf.
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertEquals(1, locationClient.activeSubscriptionCount)
        assertTrue(stoppedStartIds.isEmpty())
    }

    // 9. A stale failure callback from an older generation cannot interrupt or stop a newer recording.
    @Test
    fun `a stale failure callback from an older generation cannot stop a newer recording`() = runBlocking {
        val startGen1 = controller.beginCommand()
        controller.handleStart(startGen1, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        // A newer command is dispatched (bumping the generation) before gen1's own registration fails.
        val gen2 = controller.beginCommand()

        // gen1's collection now fails - genuinely, so trip1 IS honestly marked interrupted (that
        // part is correct and generation-independent - see the controller's own doc comment)...
        locationClient.failActiveCollection(IllegalStateException("registration lost"))
        awaitTripState(tripId, TripState.INTERRUPTED)

        // ...but the stale gen1 failure must not have touched the service on gen2's behalf.
        assertTrue(stoppedStartIds.isEmpty())

        // gen2 can still legitimately resume the very trip gen1 just (correctly) interrupted.
        controller.handleResume(tripId, gen2, startId = 2)
        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }

    // 10. A blocking flush cannot prevent Finish beyond the configured bound.
    @Test
    fun `a blocking flush cannot prevent Finish beyond the configured bound`() = runBlocking {
        val blockingClient = FakeTripLocationClient(neverCompletingFlush = true)
        val blockingCoordinator = TripRecordingCoordinator(repository, blockingClient, coordinatorScope)
        val blockingController = buildController(repository, blockingCoordinator, blockingClient)

        val startGen = blockingController.beginCommand()
        blockingController.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(blockingClient, 1)
        val tripId = repository.getActiveTripId()!!

        val finishGen = blockingController.beginCommand()
        val boundMillis = TripRecordingCommandController.FLUSH_TIMEOUT_MILLIS +
            TripRecordingCommandController.FINISH_GRACE_PERIOD_MILLIS + 2_000L
        withTimeout(boundMillis) {
            blockingController.handleFinish(finishGen, startId = 1)
        }

        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
        assertEquals(listOf(1), stoppedStartIds)
    }

    // 11. Normal stop/cancellation never invokes the genuine failure path.
    @Test
    fun `a normal Finish transitions the trip to FINISHED, never INTERRUPTED via the failure path`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        val finishGen = controller.beginCommand()
        controller.handleFinish(finishGen, startId = 1)

        assertEquals(TripState.FINISHED.name, repository.getTrip(tripId)!!.state)
        assertEquals(listOf(1), stoppedStartIds)
        assertEquals(0, locationClient.activeSubscriptionCount)
    }

    // Rapid Finish followed by a new Start - the physical-device-adjacent unit-testable half of that scenario.
    @Test
    fun `a rapid Finish immediately followed by a new Start leaves exactly the new trip active`() = runBlocking {
        val startGen1 = controller.beginCommand()
        controller.handleStart(startGen1, startId = 1)
        awaitSubscriptionCount(1)
        val trip1 = repository.getActiveTripId()!!

        val finishGen = controller.beginCommand()
        controller.handleFinish(finishGen, startId = 1)
        assertEquals(TripState.FINISHED.name, repository.getTrip(trip1)!!.state)

        val startGen2 = controller.beginCommand()
        controller.handleStart(startGen2, startId = 2)
        awaitSubscriptionCount(1)
        val trip2 = repository.getActiveTripId()!!

        assertNotEquals(trip1, trip2)
        assertEquals(1, locationClient.activeSubscriptionCount)
    }
}
