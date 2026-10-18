package com.example.stepsplit.data.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.domain.trip.RawLocationSample
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [TripRecordingCoordinator] against a real in-memory [TripRepository]/Room database and
 * a [FakeTripLocationClient] - no [android.app.Service] or Robolectric service shadow involved;
 * Robolectric here is only for the Room database, the same as every other repository test in this
 * suite.
 *
 * Deliberately uses [runBlocking] with a real (non-test-scheduler) [coordinatorScope], not
 * `kotlinx.coroutines.test.runTest`: [TripRecordingCoordinator.start] launches its collecting
 * coroutine on the scope it's given, independent of whichever coroutine called `start`, and the
 * actual DB write it eventually triggers happens on Room's own real background executor - a
 * virtual-time `TestDispatcher` has no way to know about that real, externally-driven completion,
 * so asserting the result would be racy no matter how it was pumped. Waiting on Room's own reactive
 * `Flow` (which genuinely suspends until Room delivers, real dispatcher hops included) is what
 * makes these assertions deterministic instead.
 */
@RunWith(RobolectricTestRunner::class)
class TripRecordingCoordinatorTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var repository: TripRepository
    private lateinit var locationClient: FakeTripLocationClient
    private lateinit var coordinatorScope: CoroutineScope
    private val fixedNow = Instant.parse("2026-03-10T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        repository = TripRepository(database, clock)
        locationClient = FakeTripLocationClient()
        coordinatorScope = CoroutineScope(Dispatchers.Default + Job())
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
    }

    private suspend fun awaitSubscriptionCount(expected: Int, timeoutMs: Long = 2_000) {
        withTimeout(timeoutMs) {
            while (locationClient.activeSubscriptionCount != expected) yield()
        }
    }

    @Test
    fun `no location client subscription exists before start is called`() = runBlocking {
        TripRecordingCoordinator(repository, locationClient, coordinatorScope)
        assertEquals(0, locationClient.activeSubscriptionCount)
    }

    @Test
    fun `start begins collecting and forwards accepted points to the repository`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)

        coordinator.start(tripId)
        awaitSubscriptionCount(1)

        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, fixedNow.epochSecond + 10)))

        val points = withTimeout(5_000) { database.tripPointDao().observeForTrip(tripId).first { it.isNotEmpty() } }
        assertEquals(1, points.size)
        coordinator.stop()
    }

    @Test
    fun `start is idempotent - a second call while active does not create a duplicate subscription`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)

        coordinator.start(tripId)
        awaitSubscriptionCount(1)
        coordinator.start(tripId)

        assertEquals(1, locationClient.activeSubscriptionCount)
        coordinator.stop()
    }

    @Test
    fun `stop removes the location subscription`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)

        coordinator.start(tripId)
        awaitSubscriptionCount(1)
        coordinator.stop()

        awaitSubscriptionCount(0)
    }

    /**
     * [TripRecordingCommandController.startCollecting] always calls [TripRecordingCoordinator.stop]
     * immediately followed by [TripRecordingCoordinator.start], with no wait in between for the old
     * subscription's own asynchronous teardown (see that function's own doc comment: "coordinator.stop()
     * is idempotent either way"). [TripRecordingCoordinator.stop] only requests cancellation - it does
     * not, and is not required to, wait for [FakeTripLocationClient]'s `awaitClose` to actually run
     * before returning (see its own doc comment) - so this reproduces that exact shape directly against
     * the coordinator, without a wait between stop and the following start, and proves the outcome
     * still settles to exactly one live subscription: never a lingering duplicate from the old
     * subscription's delayed teardown, and never zero because the new start raced ahead of it.
     */
    @Test
    fun `a rapid stop immediately followed by start settles to exactly one subscription, never a duplicate`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)

        coordinator.start(tripId)
        awaitSubscriptionCount(1)
        val firstSubscription = locationClient.latestSubscriptionId

        coordinator.stop()
        coordinator.start(tripId)

        // The new collection, not the old one still winding down: that one can be the only one
        // active for a moment, before the count drops to zero and the new one arrives.
        withTimeout(2_000) {
            while (locationClient.activeSubscriptionIds.let { it.size != 1 || it.single() <= firstSubscription }) yield()
        }
        // Give the old subscription's own asynchronous teardown a further moment to also settle, then
        // confirm the count is still exactly one - not two (a lingering duplicate) and not zero (both
        // torn down).
        delay(200)
        assertEquals(1, locationClient.activeSubscriptionCount)
        coordinator.stop()
    }

    @Test
    fun `stop is idempotent when the coordinator was never started`() = runBlocking {
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)
        coordinator.stop()
        assertEquals(0, locationClient.activeSubscriptionCount)
    }

    @Test
    fun `a batch emitted after stop is never forwarded to the repository`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)
        coordinator.start(tripId)
        awaitSubscriptionCount(1)
        coordinator.stop()
        awaitSubscriptionCount(0)

        // No listener remains registered, so this can only ever be a synchronous no-op.
        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, fixedNow.epochSecond + 10)))

        assertEquals(0, database.tripPointDao().getAllForTrip(tripId).size)
    }

    @Test
    fun `a failure during registration invokes onFailure and never starts collecting`() = runBlocking {
        val tripId = repository.startTrip()
        val failingClient = FakeTripLocationClient(registrationFailure = IllegalStateException("registration rejected"))
        val coordinator = TripRecordingCoordinator(repository, failingClient, coordinatorScope)
        var observedFailure: Throwable? = null

        coordinator.start(tripId) { throwable -> observedFailure = throwable }
        withTimeout(2_000) { while (observedFailure == null) yield() }

        assertEquals("registration rejected", observedFailure?.message)
        assertEquals(0, database.tripPointDao().getAllForTrip(tripId).size)
    }

    /**
     * Models an actual, unexpected exception terminating an already-active collection (e.g. a
     * permission revoked mid-trip) - a genuine failure, distinct from transient GPS
     * unavailability/no fresh fixes, which is not a Flow failure at all and has no equivalent here
     * on purpose (see [FakeTripLocationClient]'s and [FusedTripLocationClient]'s doc comments).
     */
    @Test
    fun `an actual exception mid-collection invokes onFailure and stops collecting`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)
        var observedFailure: Throwable? = null

        coordinator.start(tripId) { throwable -> observedFailure = throwable }
        awaitSubscriptionCount(1)
        locationClient.emit(listOf(RawLocationSample(32.0, 34.0, 10f, fixedNow.epochSecond + 10)))
        withTimeout(5_000) { database.tripPointDao().observeForTrip(tripId).first { it.isNotEmpty() } }

        locationClient.failActiveCollection(SecurityException("location permission revoked"))
        withTimeout(2_000) { while (observedFailure == null) yield() }

        assertEquals("location permission revoked", observedFailure?.message)
        awaitSubscriptionCount(0)
    }

    @Test
    fun `a normal stop never invokes onFailure`() = runBlocking {
        val tripId = repository.startTrip()
        val coordinator = TripRecordingCoordinator(repository, locationClient, coordinatorScope)
        var observedFailure: Throwable? = null

        coordinator.start(tripId) { throwable -> observedFailure = throwable }
        awaitSubscriptionCount(1)
        coordinator.stop()
        awaitSubscriptionCount(0)

        // Give any spurious onFailure invocation a chance to land before asserting its absence.
        withTimeout(1_000) { delay(100) }
        assertEquals(null, observedFailure)
    }

    /**
     * A [TripLocationClient] whose first collection fails on demand - after it has already been
     * stopped - and whose later collections stay open, counting live subscriptions. The failure is
     * resumed through a plain (non-cancellable) [suspendCoroutine], so the stopped collector still
     * runs its `catch` block: deterministically reproducing a failure that was already in flight on
     * another thread when the caller's stop() and newer start() happened.
     */
    private class LateFailingLocationClient : TripLocationClient {
        var liveSubscriptions = 0
            private set
        private var calls = 0
        private var pendingFailure: Continuation<Unit>? = null

        fun releaseFailure() {
            pendingFailure?.resume(Unit)
        }

        override fun locationUpdates(): Flow<List<RawLocationSample>> = if (calls++ == 0) {
            flow {
                suspendCoroutine<Unit> { pendingFailure = it }
                throw IllegalStateException("simulated location provider failure")
            }
        } else {
            callbackFlow {
                liveSubscriptions++
                awaitClose { liveSubscriptions-- }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a superseded collector failing late cannot orphan the newer collector - stop still ends its subscription`() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob())
        val client = LateFailingLocationClient()
        val coordinator = TripRecordingCoordinator(repository, client, scope)

        coordinator.start(tripId = 1)
        scheduler.runCurrent()
        client.releaseFailure()
        // Exactly what TripRecordingCommandController.startCollecting does for a newer command.
        coordinator.stop()
        coordinator.start(tripId = 1)
        scheduler.runCurrent()

        assertEquals(1, client.liveSubscriptions)
        assertTrue("the newer collector must still be tracked", coordinator.isActive)

        coordinator.stop()
        scheduler.runCurrent()
        assertEquals("stop() must end the newer collector's location subscription", 0, client.liveSubscriptions)
        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `an onFailure handler that throws never escapes the collecting scope`() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        // The production scope (AppContainer's tripRecordingScope) has no handler, so anything
        // reaching this one would have crashed the app.
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e })
        val coordinator = TripRecordingCoordinator(repository, FakeTripLocationClient(registrationFailure = SecurityException("simulated")), scope)
        var handlerRan = false

        coordinator.start(tripId = 1) {
            handlerRan = true
            throw IllegalStateException("simulated failure inside the failure handler")
        }
        scheduler.runCurrent()

        assertTrue(handlerRan)
        assertTrue("a failing onFailure handler must not escape: $uncaught", uncaught.isEmpty())
        scope.cancel()
    }
}
