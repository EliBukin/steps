package com.example.stepsplit.trip.service

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.trip.FakeTripLocationClient
import com.example.stepsplit.data.trip.TripRecordingCoordinator
import com.example.stepsplit.data.trip.TripRecordingRepository
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RawLocationSample
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * A deterministic rendezvous point for pausing a coroutine at an exact suspend boundary and
 * resuming it on command - never a sleep. [pauseHere] is called from inside the code under test
 * (via [InterceptingRepository]); the test thread drives it with [awaitEntered] (block until the
 * paused code has actually reached [pauseHere]), [release] (let it continue), and [awaitCompleted]
 * (block until the wrapped call has fully returned).
 */
private class Handoff {
    private val entered = CompletableDeferred<Unit>()
    private val releaseSignal = CompletableDeferred<Unit>()
    private val completed = CompletableDeferred<Unit>()

    suspend fun pauseHere() {
        entered.complete(Unit)
        releaseSignal.await()
    }

    suspend fun awaitEntered(timeoutMs: Long = 5_000) = withTimeout(timeoutMs) { entered.await() }

    fun release() {
        releaseSignal.complete(Unit)
    }

    fun markCompleted() {
        completed.complete(Unit)
    }

    suspend fun awaitCompleted(timeoutMs: Long = 5_000) = withTimeout(timeoutMs) { completed.await() }
}

/**
 * Delegates every [TripRecordingRepository] call unchanged to [delegate] except the (at most) two
 * intercepted below, each optionally paused via its own [Handoff] - this is the "narrow production
 * interface" [TripRecordingRepository] exists for: it lets a test deterministically suspend
 * [TripRecordingCommandController] mid-flight at an exact repository-call boundary without a fake
 * Room-backed implementation or a sleep, while every other call still hits the real
 * [TripRepository] passed as [delegate] so the rest of the scenario behaves identically to
 * production.
 */
private class InterceptingRepository(
    private val delegate: TripRecordingRepository,
    private val markTripInterruptedIfStillOwnedHandoff: Handoff? = null,
    private val claimTripForStartHandoff: Handoff? = null,
    private val claimTripForResumeHandoff: Handoff? = null,
) : TripRecordingRepository by delegate {
    override suspend fun markTripInterruptedIfStillOwned(token: Long, isCurrent: () -> Boolean): Boolean {
        markTripInterruptedIfStillOwnedHandoff?.pauseHere()
        val result = delegate.markTripInterruptedIfStillOwned(token, isCurrent)
        markTripInterruptedIfStillOwnedHandoff?.markCompleted()
        return result
    }

    override suspend fun claimTripForStart(token: Long, isCurrent: () -> Boolean): Long? {
        val result = delegate.claimTripForStart(token, isCurrent)
        claimTripForStartHandoff?.pauseHere()
        claimTripForStartHandoff?.markCompleted()
        return result
    }

    override suspend fun claimTripForResume(tripId: Long, token: Long, isCurrent: () -> Boolean): Long? {
        claimTripForResumeHandoff?.pauseHere()
        val result = delegate.claimTripForResume(tripId, token, isCurrent)
        claimTripForResumeHandoff?.markCompleted()
        return result
    }
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
        repository: TripRecordingRepository,
        coordinator: TripRecordingCoordinator,
        locationClient: FakeTripLocationClient,
        reconciliationScope: CoroutineScope = coordinatorScope,
    ) = TripRecordingCommandController(
        repository = repository,
        coordinator = coordinator,
        locationClient = locationClient,
        clock = clock,
        onStopRequested = { startId -> stoppedStartIds.add(startId) },
        reconciliationScope = reconciliationScope,
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
        awaitSubscriptionCount(0)
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
        // coordinator.stop() only requests cancellation of the collecting job; the fake client's own
        // unsubscription happens asynchronously as that cancellation is actually processed on its
        // real (non-test-scheduler) dispatcher - see TripRecordingCoordinator.stop's own doc comment
        // and awaitSubscriptionCount's. An immediate assertEquals here raced that teardown.
        awaitSubscriptionCount(0)
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
        awaitSubscriptionCount(0)
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
        awaitSubscriptionCount(0)
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

    /**
     * Combines two things `startCollecting` and `stopIfCurrent` do back to back with no wait between
     * them - `coordinator.stop()` immediately followed by a fresh `coordinator.start()` on the very
     * next command - and proves neither the asynchronous nature of the old subscription's own teardown
     * (see [TripRecordingCoordinator.stop]'s doc comment) nor `startCollecting`'s own defensive extra
     * `coordinator.stop()` call before every start ever produces a lingering duplicate subscription or
     * an extra/missing `onStopRequested` invocation: exactly one live subscription survives (never
     * two, from the old one's delayed teardown; never zero, from the new start racing ahead of it),
     * and the stop callback fires exactly once, for the failed Resume alone - never again for the
     * unrelated, fully successful Start that immediately follows it.
     */
    @Test
    fun `a rapid stop immediately followed by a new Start settles to exactly one subscription and issues the stop callback exactly once`() = runBlocking {
        val startGen1 = controller.beginCommand()
        controller.handleStart(startGen1, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!
        repository.finishTrip(tripId, clock.instant.epochSecond)

        // A failed Resume (the trip is FINISHED, not INTERRUPTED) stops the old collector via
        // stopIfCurrent, immediately followed - with no wait for that stop's own async teardown - by a
        // brand-new Start, exactly the shape startCollecting's own "coordinator.stop() then
        // coordinator.start(), idempotent either way" pattern is meant to handle.
        val resumeGen = controller.beginCommand()
        controller.handleResume(tripId, resumeGen, startId = 2)

        val startGen2 = controller.beginCommand()
        controller.handleStart(startGen2, startId = 3)
        awaitSubscriptionCount(1)

        // Give the old (failed-Resume) subscription's own asynchronous teardown a further moment to
        // also settle, then confirm the count is still exactly one.
        delay(200)
        assertEquals(1, locationClient.activeSubscriptionCount)
        assertEquals(listOf(2), stoppedStartIds)
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
        // coordinator.stop()'s cancellation is processed asynchronously - the fake client's own
        // unsubscription happens once that cancellation actually runs, not synchronously with the
        // stop() call itself - hence awaitSubscriptionCount rather than an immediate assertEquals here.
        awaitSubscriptionCount(0)
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

    // Required regression test: a stale failure's atomic mutation is genuinely paused - not merely
    // stale before it starts (see the class's own historical test 9b above, which reserves the newer
    // generation *before* triggering the failure and therefore never exercises this gap) - while a
    // newer collector fully, successfully takes over the very same (idempotently reused) ACTIVE trip.
    // TripRecordingRepository.markTripInterruptedIfStillOwned's atomic owner comparison, evaluated at
    // the actual mutation point rather than via a caller-side isCurrent() check before this suspending
    // call, is what must make the older failure's eventual mutation a complete no-op.
    @Test
    fun `a delayed stale failure paused inside its own atomic mutation cannot interrupt a trip a newer collector has already taken over`() = runBlocking {
        val handoff = Handoff()
        val interceptingRepository = InterceptingRepository(delegate = repository, markTripInterruptedIfStillOwnedHandoff = handoff)
        val racyController = buildController(interceptingRepository, coordinator, locationClient)

        val startGenA = racyController.beginCommand()
        racyController.handleStart(startGenA, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        // A's collector fails - its failure handler enters handleRecordingFailure, which the
        // intercepting repository pauses immediately before its atomic ownership-checked mutation.
        locationClient.failActiveCollection(IllegalStateException("registration lost"))
        handoff.awaitEntered()

        // B starts fresh for the same trip - still ACTIVE, since A's delayed mutation has not
        // committed yet - and B's own atomic claim (claimTripForStart) genuinely completes and wins
        // ownership before A is released.
        val startGenB = racyController.beginCommand()
        racyController.handleStart(startGenB, startId = 2)
        awaitSubscriptionCount(1)
        assertEquals(tripId, repository.getActiveTripId())

        // Release A's paused failure and wait for its (now-stale) mutation attempt to fully resolve.
        handoff.release()
        handoff.awaitCompleted()

        // A complete no-op: no Room state mutation (trip is still ACTIVE), B's collector is
        // untouched, and A never requested service teardown.
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertEquals(1, locationClient.activeSubscriptionCount)
        assertTrue(stoppedStartIds.isEmpty())

        // A point from B is genuinely persisted, proving B's collector is still the live one.
        val postFailureTime = fixedNow.epochSecond + 30
        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, postFailureTime)))
        withTimeout(5_000) { repository.observeTripPoints(tripId).first { it.isNotEmpty() } }
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    // Required regression test: a Start handler paused after its last repository suspension
    // (claimTripForStart, whose atomic claim has already committed and registered ownership by this
    // point) but before the synchronous coordinator.start() call - the exact gap
    // TripRecordingCommandController.shutdown's terminal gate closure must close, since cancelling
    // the previous command's coroutine alone cannot stop a handler that has already returned from its
    // last suspend point (see the class's own top-level doc comment). Also proves shutdown's own
    // reconciliation honestly cleans up the trip this claim left ACTIVE-but-uncollected, since
    // coordinator.start() never got to run for it.
    @Test
    fun `a Start handler paused after its last repository suspension cannot start a collector once shutdown has run`() = runBlocking {
        val handoff = Handoff()
        val interceptingRepository = InterceptingRepository(delegate = repository, claimTripForStartHandoff = handoff)
        val shutdownController = buildController(interceptingRepository, coordinator, locationClient)

        val startGen = shutdownController.beginCommand()
        val handlerJob = launch { shutdownController.handleStart(startGen, startId = 1) }
        handoff.awaitEntered()
        // The atomic claim already committed and registered ownership by this point - the trip exists
        // and is ACTIVE, even though coordinator.start() has not run yet.
        val tripId = repository.getActiveTripId()!!
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)

        shutdownController.shutdown()
        handoff.release()
        withTimeout(5_000) { handlerJob.join() }

        awaitSubscriptionCount(0)
        assertTrue(stoppedStartIds.isEmpty())
        // No ghost ACTIVE trip survives: shutdown's own reconciliation honestly interrupts the trip
        // this paused claim left ACTIVE with no collector ever actually started for it.
        awaitTripState(tripId, TripState.INTERRUPTED)
    }

    // Required regression test: an old action already inside the atomic gate must finish before
    // shutdown performs its final stop, and the gate must permanently reject everything afterward -
    // proven at the gate-primitive level with real thread concurrency in
    // CommandGenerationGateTest.`shutdown waits for an in-flight action to finish...`; this test
    // proves the *controller*-level wiring of that same guarantee using a real, already-started
    // collector instead of a synthetic gate action.
    @Test
    fun `shutdown waits for an in-flight command to finish before performing its own final stop`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        controller.shutdown()

        // The final observable state is stopped: no collector left running (coordinator.stop()
        // cancels the collecting job; the fake client's own unsubscription happens asynchronously as
        // that cancellation is actually processed, hence awaitSubscriptionCount rather than an
        // immediate assertEquals here), and the trip left with no collector behind it is honestly
        // reconciled to INTERRUPTED (see the ghost-trip test below for that specifically), never
        // silently left claiming to record.
        awaitSubscriptionCount(0)
        awaitTripState(tripId, TripState.INTERRUPTED)

        // The gate is now permanently closed: a fresh command reserved on the very same controller
        // instance can never start a collector again.
        val genAfterShutdown = controller.beginCommand()
        controller.handleStart(genAfterShutdown, startId = 2)
        awaitSubscriptionCount(0)
    }

    // Required regression test: an in-flight command that created/resumed an ACTIVE trip immediately
    // before shutdown must not leave a ghost ACTIVE trip with no collector behind it.
    @Test
    fun `shutdown honestly reconciles a trip left ACTIVE with no collector to INTERRUPTED - no ghost ACTIVE trip survives`() = runBlocking {
        val shutdownController = buildController(repository, coordinator, locationClient)
        val startGen = shutdownController.beginCommand()
        shutdownController.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        shutdownController.shutdown()

        awaitTripState(tripId, TripState.INTERRUPTED)
        awaitSubscriptionCount(0)
    }

    // Required regression test: two separate controller instances (simulating two
    // TripRecordingService instances, e.g. across a stop-then-restart within the same process) must
    // never issue colliding Finish-cutoff tokens. Before CommandGenerationGate drew every generation
    // from a single process-wide counter, each instance's gate numbered its own generations from
    // scratch, so both calls below could easily have both returned "1" - in which case A's delayed
    // cleanup, cancelFinish(1), would have cleared B's still-live cutoff too, since cancelFinish only
    // ever compares by numeric token equality.
    @Test
    fun `two controller instances never collide on Finish-cutoff tokens - an old delayed cleanup cannot clear a newer instance's live cutoff`() = runBlocking {
        val controllerA = buildController(repository, coordinator, locationClient)
        val controllerB = buildController(repository, coordinator, locationClient)
        val tripId = repository.startTrip()
        val t0 = fixedNow.epochSecond

        val finishGenA = controllerA.beginCommand()
        val finishGenB = controllerB.beginCommand()
        assertNotEquals(finishGenA, finishGenB)

        repository.beginFinish(tripId, finishGenA, t0 + 10) // A's (older) Finish cutoff
        repository.beginFinish(tripId, finishGenB, t0 + 100) // B's (newer) Finish cutoff supersedes it

        // A's own delayed NonCancellable cleanup (see handleFinish's `finally` block) finally runs,
        // long after A was superseded.
        repository.cancelFinish(finishGenA)

        // A point after A's cutoff (10) but before B's (100) is still accepted - B's cutoff, not A's,
        // is what's in effect, proving A's cleanup did not clear it.
        repository.recordAcceptedBatch(tripId, listOf(RawLocationSample(32.0, 34.0, 10f, t0 + 50)))
        assertEquals(1, repository.getTripPoints(tripId).size)

        // A point after B's own cutoff (100) is still correctly rejected.
        repository.recordAcceptedBatch(tripId, listOf(RawLocationSample(32.001, 34.0, 10f, t0 + 150)))
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    // Required regression test - reproduces the exact previously-failing interleaving: Start/Resume
    // resolving/claiming a trip and registering ownership used to be two separate suspending steps
    // (a repository trip-resolve call, then later a separate beginRecording() call), leaving a real
    // gap in which a *different*, shutting-down controller's own reconciliation could observe the
    // trip as ACTIVE-with-no-owner-yet and interrupt it, even though a genuinely newer collector was
    // about to (or already had) taken it over. TripRecordingRepository.claimTripForStart now performs
    // trip resolution and ownership registration as one atomic step, so this can no longer happen -
    // proven here by pausing controller A's shutdown reconciliation right before its own atomic
    // mutation while controller B, a distinct instance, fully and successfully claims the same
    // (idempotently reused) trip and starts collecting for it.
    @Test
    fun `a stale shutdown reconciliation paused before its mutation cannot interrupt a trip a newer controller has already atomically claimed and started collecting for`() = runBlocking {
        val handoff = Handoff()
        val interceptingRepository = InterceptingRepository(delegate = repository, markTripInterruptedIfStillOwnedHandoff = handoff)
        val controllerA = buildController(interceptingRepository, coordinator, locationClient)

        val startGenA = controllerA.beginCommand()
        controllerA.handleStart(startGenA, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!

        // A shuts down: the gate closes and A's own collector is synchronously stopped, but the trip
        // reconciliation itself is paused right before its atomic mutation - exactly the gap the old
        // separate trip-resolve+markTripInterruptedIfStillOwned pairing left open.
        controllerA.shutdown()
        handoff.awaitEntered()
        awaitSubscriptionCount(0) // A's own collector already stopped

        // B - a distinct controller instance, e.g. a freshly (re)started service - receives a new
        // Start while A's reconciliation is still paused. It idempotently reuses the same still-ACTIVE
        // trip and fully, successfully claims and starts collecting for it before A's reconciliation
        // is released - proving the atomic claim, not mere timing, is what makes this safe.
        val controllerB = buildController(repository, coordinator, locationClient)
        val startGenB = controllerB.beginCommand()
        controllerB.handleStart(startGenB, startId = 2)
        awaitSubscriptionCount(1)
        assertEquals(tripId, repository.getActiveTripId())

        handoff.release()
        handoff.awaitCompleted()

        // A complete no-op: the trip is still ACTIVE, exactly one live subscription (B's) exists, and
        // A never requested teardown of B's service instance.
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertEquals(1, locationClient.activeSubscriptionCount)
        assertTrue(stoppedStartIds.isEmpty())

        // A valid point from B's collector is genuinely persisted, proving B's collector is the live one.
        val postRaceTime = fixedNow.epochSecond + 10
        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, postRaceTime)))
        withTimeout(5_000) { repository.observeTripPoints(tripId).first { it.isNotEmpty() } }
        assertEquals(1, repository.getTripPoints(tripId).size)
    }

    // Required regression test: the reverse ordering - shutdown's reconciliation completes in full
    // *before* a new Start's atomic claim even begins. No ghost ACTIVE trip or collector survives that
    // reconciliation on its own, and the follow-on Start then cleanly creates and owns its own fresh
    // trip, never colliding with the now-INTERRUPTED one.
    @Test
    fun `if shutdown's reconciliation completes before a new Start's claim begins, no ghost trip survives and the new Start cleanly creates its own`() = runBlocking {
        val handoff = Handoff()
        val interceptingRepository = InterceptingRepository(delegate = repository, markTripInterruptedIfStillOwnedHandoff = handoff)
        val controllerA = buildController(interceptingRepository, coordinator, locationClient)

        val startGenA = controllerA.beginCommand()
        controllerA.handleStart(startGenA, startId = 1)
        awaitSubscriptionCount(1)
        val tripX = repository.getActiveTripId()!!

        controllerA.shutdown()
        handoff.awaitEntered()
        handoff.release()
        handoff.awaitCompleted() // A's reconciliation fully commits before B ever starts

        awaitTripState(tripX, TripState.INTERRUPTED)
        awaitSubscriptionCount(0)

        val controllerB = buildController(repository, coordinator, locationClient)
        val startGenB = controllerB.beginCommand()
        controllerB.handleStart(startGenB, startId = 2)
        awaitSubscriptionCount(1)
        val tripY = repository.getActiveTripId()!!

        assertNotEquals(tripX, tripY)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripY)!!.state)
        assertEquals(1, locationClient.activeSubscriptionCount)

        val point = RawLocationSample(32.0, 34.0, 10f, fixedNow.epochSecond + 10)
        locationClient.emit(listOf(point))
        withTimeout(5_000) { repository.observeTripPoints(tripY).first { it.isNotEmpty() } }
        assertEquals(1, repository.getTripPoints(tripY).size)
    }

    // Required regression test - the Resume sibling of the Start race above: the same
    // stale-reconciliation-vs-newer-claim overlap, but for TripRecordingRepository.claimTripForResume,
    // proving the resumed trip's existing route/distance survive untouched.
    @Test
    fun `the same stale-reconciliation-vs-newer-claim race for Resume leaves the resumed trip ACTIVE and preserves its route and distance`() = runBlocking {
        val handoff = Handoff()
        val interceptingRepository = InterceptingRepository(delegate = repository, markTripInterruptedIfStillOwnedHandoff = handoff)
        val controllerA = buildController(interceptingRepository, coordinator, locationClient)

        val startGen = controllerA.beginCommand()
        controllerA.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!
        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, fixedNow.epochSecond + 10)))
        withTimeout(5_000) { repository.observeTripPoints(tripId).first { it.isNotEmpty() } }
        val distanceBefore = repository.getTrip(tripId)!!.distanceMeters
        val pointsBefore = repository.getTripPoints(tripId).size

        // The app relaunches while A's service is presumed dead - the trip is reconciled to
        // INTERRUPTED independently of A's own (not-yet-called) shutdown, exactly like a real process
        // death would.
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)

        // A's own shutdown (e.g. the OS finally tearing down the old process's service instance) now
        // runs, its reconciliation paused right before its atomic mutation.
        controllerA.shutdown()
        handoff.awaitEntered()

        // B - a distinct, freshly (re)started controller - Resumes the same trip while that stale
        // reconciliation is still paused, and fully, successfully claims and starts collecting for it
        // before A's reconciliation is released.
        val controllerB = buildController(repository, coordinator, locationClient)
        val resumeGen = controllerB.beginCommand()
        controllerB.handleResume(tripId, resumeGen, startId = 2)
        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)

        handoff.release()
        handoff.awaitCompleted()

        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
        assertEquals(distanceBefore, repository.getTrip(tripId)!!.distanceMeters, 1e-9)
        assertEquals(pointsBefore, repository.getTripPoints(tripId).size)
        assertEquals(1, locationClient.activeSubscriptionCount)
        assertTrue(stoppedStartIds.isEmpty())
    }

    // Required regression test (bug report): generation currency was not atomic with the claim
    // mutation. A Resume generation paused right before its repository claim - after already passing
    // the controller's upfront isCurrent(generation) check - must not be able to complete that claim
    // once a newer generation has since been reserved on the very same gate, even though that newer
    // generation's own handler has not run yet and has therefore registered no ownership at all.
    // Deliberately never cancels genOld's handler job - it is allowed to run to completion on its own,
    // so cancellation is provably not what makes this safe.
    @Test
    fun `a Resume paused right before its claim is rejected once a newer generation is merely reserved, without relying on cancellation`() = runBlocking {
        val startGen = controller.beginCommand()
        controller.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = repository.getActiveTripId()!!
        repository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, repository.getTrip(tripId)!!.state)
        // The original service's own collector is gone, mirroring the real process death this
        // reconciliation models - without this, the still-running original collector would trivially
        // satisfy the final subscription-count assertion below regardless of how the race resolves.
        coordinator.stop()
        awaitSubscriptionCount(0)

        val handoff = Handoff()
        val interceptingRepository = InterceptingRepository(delegate = repository, claimTripForResumeHandoff = handoff)
        val racyController = buildController(interceptingRepository, coordinator, locationClient)

        val resumeGenOld = racyController.beginCommand()
        val handlerJob = launch { racyController.handleResume(tripId, resumeGenOld, startId = 2) }
        handoff.awaitEntered() // genOld passed its upfront isCurrent check and is paused right before its claim

        // A newer generation is synchronously reserved on the very same gate - exactly as a real
        // onStartCommand would - but its own handler has not run at all yet, so no ownership has been
        // registered for it anywhere.
        val resumeGenNew = racyController.beginCommand()

        handoff.release()
        withTimeout(5_000) { handlerJob.join() } // genOld's own handler runs to completion, uncancelled

        // The genuinely current generation now runs its own Resume for the same trip.
        racyController.handleResume(tripId, resumeGenNew, startId = 3)

        // The trip must end up ACTIVE with exactly one live collector - never ACTIVE with zero
        // subscriptions, which is what genOld's claim succeeding despite being stale would produce.
        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, repository.getTrip(tripId)!!.state)
    }

    // Required regression test (bug report): the previous test above pauses *before* the repository
    // call, which only proves the newer generation is reserved before the repository's own currency
    // check ever runs - it does not exercise the later, more dangerous interleaving in which the
    // currency check has already read `true` and the pause happens immediately afterward, before any
    // Room read/write. Since `tripMutex` and `CommandGenerationGate`'s own lock are independent, that
    // snapshot is not atomic with a concurrent `beginCommand()` - see `TripRepository`'s own "Why
    // isCurrent cannot be the safety mechanism" doc comment section. Uses
    // `TripRepository`'s `afterCurrencyCheck` test seam (scoped to genOld's own token, so genNew's own
    // claim on the very same repository instance is completely unaffected) to pause at exactly that
    // point - genuinely after the currency snapshot, genuinely before the first Room operation. Never
    // cancels genOld's handler job - it runs to completion entirely on its own.
    @Test
    fun `an old Resume paused after its currency snapshot reads true, but before any Room work, is still safely superseded by a newer generation`() = runBlocking {
        var pausedToken = -1L
        val handoff = Handoff()
        val racyRepository = TripRepository(database, clock, afterCurrencyCheck = { token -> if (token == pausedToken) handoff.pauseHere() })
        val racyCoordinator = TripRecordingCoordinator(racyRepository, locationClient, coordinatorScope)
        val racyController = buildController(racyRepository, racyCoordinator, locationClient)

        val startGen = racyController.beginCommand()
        racyController.handleStart(startGen, startId = 1)
        awaitSubscriptionCount(1)
        val tripId = racyRepository.getActiveTripId()!!
        racyRepository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, racyRepository.getTrip(tripId)!!.state)
        // The original service's own collector is gone, mirroring the real process death this
        // reconciliation models - without this, the still-running original collector would trivially
        // satisfy the final subscription-count assertion below regardless of how the race resolves.
        racyCoordinator.stop()
        awaitSubscriptionCount(0)

        val resumeGenOld = racyController.beginCommand()
        pausedToken = resumeGenOld // arm the hook for exactly this generation's own claim, and no other
        val handlerJob = launch { racyController.handleResume(tripId, resumeGenOld, startId = 2) }
        handoff.awaitEntered() // genOld's own isCurrent() already read true; paused before any Room op

        // A newer generation is synchronously reserved on the very same gate while genOld is paused -
        // exactly the moment its own currency snapshot silently becomes stale.
        val resumeGenNew = racyController.beginCommand()

        handoff.release()
        withTimeout(5_000) { handlerJob.join() } // genOld's own handler runs to completion, uncancelled

        // The genuinely current generation now runs its own Resume for the same trip - regardless of
        // whether genOld's own (now-stale) claim already committed the trip to ACTIVE in the meantime.
        racyController.handleResume(tripId, resumeGenNew, startId = 3)

        // The trip must end up ACTIVE with exactly one live collector - never ACTIVE with zero
        // subscriptions.
        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, racyRepository.getTrip(tripId)!!.state)
    }

    // Audit companion to the Resume regression above: claimTripForStart was already convergent by
    // construction (it always operates on "whichever trip is ACTIVE, or create one," never asserting an
    // exclusive precondition a racing older claim could invalidate - see TripRepository's own class doc
    // comment). This empirically proves that reasoning under the identical race, rather than leaving it
    // as an unverified claim: an old Start paused genuinely after its own currency snapshot reads true,
    // but before any Room work, must still converge safely once a newer generation takes over.
    @Test
    fun `an old Start paused after its currency snapshot reads true, but before any Room work, is still safely superseded by a newer generation`() = runBlocking {
        var pausedToken = -1L
        val handoff = Handoff()
        val racyRepository = TripRepository(database, clock, afterCurrencyCheck = { token -> if (token == pausedToken) handoff.pauseHere() })
        val racyCoordinator = TripRecordingCoordinator(racyRepository, locationClient, coordinatorScope)
        val racyController = buildController(racyRepository, racyCoordinator, locationClient)

        val startGenOld = racyController.beginCommand()
        pausedToken = startGenOld
        val handlerJob = launch { racyController.handleStart(startGenOld, startId = 1) }
        handoff.awaitEntered() // genOld's own isCurrent() already read true; paused before any Room op

        val startGenNew = racyController.beginCommand()

        handoff.release()
        withTimeout(5_000) { handlerJob.join() }

        racyController.handleStart(startGenNew, startId = 2)

        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, racyRepository.getTrip(racyRepository.getActiveTripId()!!)!!.state)
        assertEquals(1, database.tripDao().observeAll().first().size) // exactly one trip, never a duplicate
    }

    // Required regression test (bug report): the convergent-takeover branch above only handles a newer
    // command re-targeting the *same* trip an older, racing claim already committed ACTIVE. It does
    // nothing for a newer command targeting a *different* trip: an old Resume for trip A, delayed past
    // its own currency snapshot, can still commit A to ACTIVE; a newer Resume for a completely different
    // trip B then finds A occupying the single ACTIVE slot and - without the fix below - is rejected
    // outright, stopping the service with A left falsely ACTIVE and nothing actually recording it.
    // Never cancels genOld's handler job - it runs to completion entirely on its own.
    @Test
    fun `an old Resume for trip A paused after its currency snapshot is safely superseded by a newer Resume for a different trip B`() = runBlocking {
        var pausedToken = -1L
        val handoff = Handoff()
        val racyRepository = TripRepository(database, clock, afterCurrencyCheck = { token -> if (token == pausedToken) handoff.pauseHere() })
        val racyCoordinator = TripRecordingCoordinator(racyRepository, locationClient, coordinatorScope)
        val racyController = buildController(racyRepository, racyCoordinator, locationClient)

        // Two distinct, already-INTERRUPTED trips, from two earlier sessions.
        val tripA = racyRepository.startTrip()
        racyRepository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        val tripB = racyRepository.startTrip() // A is INTERRUPTED, so this creates a second, distinct trip
        racyRepository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertNotEquals(tripA, tripB)
        assertEquals(TripState.INTERRUPTED.name, racyRepository.getTrip(tripA)!!.state)
        assertEquals(TripState.INTERRUPTED.name, racyRepository.getTrip(tripB)!!.state)

        val resumeGenOld = racyController.beginCommand()
        pausedToken = resumeGenOld
        val handlerJob = launch { racyController.handleResume(tripA, resumeGenOld, startId = 1) }
        handoff.awaitEntered() // genOld's own isCurrent() already read true; paused before any Room op

        // A newer Resume for the *other* trip is reserved on the same gate while genOld is paused.
        val resumeGenNew = racyController.beginCommand()

        handoff.release()
        withTimeout(5_000) { handlerJob.join() } // genOld's own handler runs to completion, uncancelled

        racyController.handleResume(tripB, resumeGenNew, startId = 2)

        // B must end up ACTIVE with exactly one live collector; A must have been correctly reverted to
        // INTERRUPTED (never left falsely ACTIVE with nothing recording it); the database must never
        // show two ACTIVE rows; and the genuinely current handler must never have stopped the service.
        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, racyRepository.getTrip(tripB)!!.state)
        assertEquals(TripState.INTERRUPTED.name, racyRepository.getTrip(tripA)!!.state)
        val allTrips = database.tripDao().observeAll().first()
        assertEquals(1, allTrips.count { it.state == TripState.ACTIVE.name })
        assertTrue(stoppedStartIds.isEmpty())
    }

    // Required mirror of the regression above: the same cross-target race, but the older, racing command
    // is a Start (creating/reusing a trip) instead of a Resume.
    @Test
    fun `an old Start creating or reusing trip A is safely superseded by a newer Resume for a different, already-interrupted trip B`() = runBlocking {
        var pausedToken = -1L
        val handoff = Handoff()
        val racyRepository = TripRepository(database, clock, afterCurrencyCheck = { token -> if (token == pausedToken) handoff.pauseHere() })
        val racyCoordinator = TripRecordingCoordinator(racyRepository, locationClient, coordinatorScope)
        val racyController = buildController(racyRepository, racyCoordinator, locationClient)

        // Trip B already exists, INTERRUPTED, from an earlier session - no trip is ACTIVE yet.
        val tripB = racyRepository.startTrip()
        racyRepository.reconcileActiveTripOnLaunch(isServiceRunning = false)
        assertEquals(TripState.INTERRUPTED.name, racyRepository.getTrip(tripB)!!.state)

        val startGenOld = racyController.beginCommand()
        pausedToken = startGenOld
        val handlerJob = launch { racyController.handleStart(startGenOld, startId = 1) }
        handoff.awaitEntered()

        val resumeGenNew = racyController.beginCommand()

        handoff.release()
        withTimeout(5_000) { handlerJob.join() }

        // genOld's own claim already committed by now - it created a fresh trip A (none was ACTIVE) and
        // registered itself as its owner, even though it was already stale by the time it ran.
        val tripA = racyRepository.getActiveTripId()!!
        assertNotEquals(tripB, tripA)

        racyController.handleResume(tripB, resumeGenNew, startId = 2)

        awaitSubscriptionCount(1)
        assertEquals(TripState.ACTIVE.name, racyRepository.getTrip(tripB)!!.state)
        assertEquals(TripState.INTERRUPTED.name, racyRepository.getTrip(tripA)!!.state)
        val allTrips = database.tripDao().observeAll().first()
        assertEquals(2, allTrips.size)
        assertEquals(1, allTrips.count { it.state == TripState.ACTIVE.name })
        assertTrue(stoppedStartIds.isEmpty())
    }
}
