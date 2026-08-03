package com.example.stepsplit.data.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
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
        repository = TripRepository(database, clock, stepSourceId = "local_recording_api")
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
}
