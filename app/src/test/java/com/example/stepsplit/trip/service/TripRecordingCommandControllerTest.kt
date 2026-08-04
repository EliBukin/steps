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
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

private class MutableClock(var instant: Instant, private val zoneId: ZoneOffset = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zoneId
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = instant
}

/**
 * Exercises [TripRecordingCommandController] directly - the real class
 * [TripRecordingService] delegates every command to, not a copy or a re-implementation - against a
 * real in-memory [TripRepository]/Room database and [FakeTripLocationClient]. No
 * [android.app.Service] or Robolectric service shadow is involved; Robolectric here is only for the
 * Room database, the same as every other repository-level test in this suite. This is deliberately
 * the primary regression coverage for the generation-safety/Resume-atomicity races described on
 * [TripRecordingCommandController]'s own doc comment: those races are about command *ordering*, not
 * genuine Android lifecycle behavior, so they are fully and deterministically reproducible here.
 * [CommandGenerationGateTest] separately proves the atomic-ownership primitive itself under real
 * thread concurrency; this file stays at the command/generation-ordering level, which is what
 * actually matters for `TripRecordingService`'s behavior. Real Android `Service` lifecycle
 * (`onStartCommand` dispatch, `stopSelfResult`, foreground notification teardown) is *not* exercised
 * here - see [ServiceStopCoordinatorTest] for that boundary's own (also extraction-based) coverage.
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
    private lateinit var clock: MutableClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        clock = MutableClock(fixedNow)
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

    private suspend fun awaitFlushInvoked(client: FakeTripLocationClient, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) { while (!client.flushInvoked) yield() }
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
        repository.finishTrip(tripId, clock.instant.epochSecond)

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

    // 9a. A genuinely current collector's failure still honestly interrupts its own trip and stops the service.
    @Test
    fun `a genuinely current collector's failure marks its trip INTERRUPTED and stops the service`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        locationClient.failActiveCollection(IllegalStateException("registration lost"))
        awaitTripState(tripId, TripState.INTERRUPTED)

        assertEquals(listOf(1), stoppedStartIds)
        assertEquals(0, locationClient.activeSubscriptionCount)
    }

    // 9b. A failure callback whose generation was already superseded before it fires must not
    // interrupt the trip a newer command is about to (or already does) own - see finding 4: the old
    // "always mark interrupted regardless of generation" behavior let a stale failure stomp on a
    // newer collector reusing the same (idempotent) ACTIVE trip. The newer generation is reserved
    // (as onStartCommand does, synchronously, before its own handler coroutine runs) before the
    // stale failure is triggered - deliberately not "collector already fully started", because
    // starting a newer collector always calls coordinator.stop() first, which removes the old
    // collector's fake subscription and makes it impossible to also independently trigger its
    // failure afterward; reserving the generation is what actually creates the dangerous window
    // this fix must close (the failure's *mutation* racing the newer command's own work).
    @Test
    fun `a failure callback whose generation was already superseded before it fires must not interrupt the trip, and the newer command can still take over cleanly`() = runBlocking {
        val startGen1 = controller.beginCommand()
        controller.handleStart(startGen1, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        val gen2 = controller.beginCommand()

        locationClient.failActiveCollection(IllegalStateException("stale registration lost"))
        withTimeout(5_000) { while (locationClient.activeSubscriptionCount != 0) yield() }

        // The stale failure must be a complete no-op on gen2's behalf: not interrupted, not stopped.
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertTrue(stoppedStartIds.isEmpty())

        // gen2 now runs its own command - idempotently reusing the still-ACTIVE trip and starting a
        // fresh collector for it, exactly as a real newer Start would.
        controller.handleStart(gen2, startId = 2)
        awaitSubscriptionCount(1)
        assertEquals(tripId, repository.getActiveTripId())
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)

        val postFailureTime = fixedNow.epochSecond + 30
        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, postFailureTime)))
        withTimeout(5_000) { repository.observeTripPoints(tripId).first { it.isNotEmpty() } }
        assertEquals(1, repository.getTripPoints(tripId).size)
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

    // Finding 1, required test 1: cancelling a Finish that a newer Start supersedes must release only
    // that Finish's own cutoff - not leave it permanently rejecting the new collector's points.
    @Test
    fun `cancelling a Finish superseded by a newer Start releases only its own cutoff, so the new collector's points are accepted`() = runBlocking {
        val blockingClient = FakeTripLocationClient(neverCompletingFlush = true)
        val blockingCoordinator = TripRecordingCoordinator(repository, blockingClient, coordinatorScope)
        val blockingController = buildController(repository, blockingCoordinator, blockingClient)

        val startGen1 = blockingController.beginCommand()
        blockingController.handleStart(startGen1, startId = 1)
        awaitSubscriptionCount(blockingClient, 1)
        val tripId = repository.getActiveTripId()!!

        val finishGen = blockingController.beginCommand()
        val finishJob = launch { blockingController.handleFinish(finishGen, startId = 1) }
        // Deterministically wait until Finish has installed its cutoff (beginFinish runs strictly
        // before flush()) and is suspended inside the never-completing flush - no sleep needed.
        awaitFlushInvoked(blockingClient)

        // A newer Start supersedes and cancels the still-suspended Finish - exactly what the service
        // does: cancel the previous command's coroutine, then dispatch the new one.
        val startGen2 = blockingController.beginCommand()
        finishJob.cancel()
        blockingController.handleStart(startGen2, startId = 2)
        awaitSubscriptionCount(blockingClient, 1)
        finishJob.join()

        // The trip remains ACTIVE, exactly one current collector exists, and no stale stop ran.
        assertEquals(tripId, repository.getActiveTripId())
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertEquals(1, blockingClient.activeSubscriptionCount)
        assertTrue(stoppedStartIds.isEmpty())

        // A valid point captured well after the abandoned cutoff must be accepted, not rejected
        // forever - proving the abandoned cutoff is gone. (Kept within MAX_FUTURE_SKEW_SECONDS of
        // "now", since the clock is not advanced in this test.)
        val postCutoffTime = fixedNow.epochSecond + 200
        blockingClient.emit(listOf(RawLocationSample(32.5, 34.5, 10f, postCutoffTime)))
        withTimeout(5_000) { repository.observeTripPoints(tripId).first { it.isNotEmpty() } }

        assertEquals(1, repository.getTripPoints(tripId).size)
        assertEquals(postCutoffTime, repository.getTripPoints(tripId).single().capturedAtEpochSecond)
    }

    // Finding 5, required test 4: the persisted end time is the Finish request instant, never the
    // later instant the bounded flush/grace period actually finished at.
    @Test
    fun `the persisted end time is the Finish request instant, not the later time the flush and grace period actually finished`() = runBlocking {
        val blockingClient = FakeTripLocationClient(neverCompletingFlush = true)
        val blockingCoordinator = TripRecordingCoordinator(repository, blockingClient, coordinatorScope)
        val blockingController = buildController(repository, blockingCoordinator, blockingClient)

        val startGen = blockingController.beginCommand()
        blockingController.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(blockingClient, 1)
        val tripId = repository.getActiveTripId()!!

        val finishRequestedAt = clock.instant
        val finishGen = blockingController.beginCommand()
        val finishJob = launch { blockingController.handleFinish(finishGen, startId = 1) }

        awaitFlushInvoked(blockingClient)
        // The cutoff/end time has already been captured as `finishRequestedAt` by this point -
        // advancing the clock now, while Finish is still suspended in its bounded flush/grace wait,
        // must not affect the timestamp that ends up persisted.
        clock.instant = finishRequestedAt.plusSeconds(5_000)

        finishJob.join()

        val trip = repository.getTrip(tripId)!!
        assertEquals(TripState.FINISHED.name, trip.state)
        assertEquals(finishRequestedAt.epochSecond, trip.endEpochSecond)
        assertNotEquals(clock.instant.epochSecond, trip.endEpochSecond)
    }

    // Finding 6, required test 5: a foreground-promotion failure while a trip is ACTIVE reconciles it
    // to INTERRUPTED and stops the service - never silently left ACTIVE with nothing recording, and
    // never fabricated as a successful Finish.
    @Test
    fun `a foreground-promotion failure while a trip is ACTIVE reconciles it to INTERRUPTED, stops the service, and leaves no collector alive`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        // A later command's own foreground promotion fails (e.g. the OS rejecting a background
        // foreground-service start) - dispatched with its own freshly reserved generation, exactly
        // as TripRecordingService.onStartCommand's catch block does.
        val failedGen = controller.beginCommand()
        controller.handleForegroundPromotionFailure(failedGen, startId = 2)

        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
        assertEquals(listOf(2), stoppedStartIds)
        assertEquals(0, locationClient.activeSubscriptionCount)
    }

    @Test
    fun `a foreground-promotion failure with no active trip simply stops the service`() = runBlocking {
        val gen = controller.beginCommand()
        controller.handleForegroundPromotionFailure(gen, startId = 1)

        assertEquals(0, database.tripDao().observeAll().first().size)
        assertEquals(listOf(1), stoppedStartIds)
    }

    @Test
    fun `a foreground-promotion failure whose generation was already superseded is a no-op`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        val staleGen = controller.beginCommand()
        controller.beginCommand() // supersedes staleGen before its handler runs
        controller.handleForegroundPromotionFailure(staleGen, startId = 2)

        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertTrue(stoppedStartIds.isEmpty())
    }
}
