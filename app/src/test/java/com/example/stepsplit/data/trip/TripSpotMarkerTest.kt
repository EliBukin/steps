package com.example.stepsplit.data.trip

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RawLocationSample
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [TripSpotMarker] against a real in-memory Room database, a real [TripRecordingCoordinator] and a
 * [FakeTripLocationClient], with real dispatchers - the same approach as
 * [TripRecordingCoordinatorTest], for the same reason: the coordinator collects on its own scope and
 * Room writes on its own executor. The spot location rule itself is [SpotFixRuleTest]'s; this proves
 * the marker applies it with the fixes it really gets: the recording's newest, its next ones, and a
 * fresh one asked for.
 */
@RunWith(RobolectricTestRunner::class)
class TripSpotMarkerTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var repository: TripRepository
    private lateinit var locationClient: FakeTripLocationClient
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: TripRecordingCoordinator
    private val now = Instant.parse("2026-03-10T10:00:00Z").epochSecond
    private val clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        repository = TripRepository(database, clock)
        locationClient = FakeTripLocationClient()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = TripRecordingCoordinator(repository, locationClient, scope)
    }

    @After
    fun tearDown() {
        coordinator.stop()
        scope.cancel()
        database.close()
    }

    private fun marker(waitMillis: Long = 300L) = TripSpotMarker(repository, coordinator, locationClient, clock, scope, waitForFixMillis = waitMillis)

    private fun fix(capturedAt: Long, lat: Double = 31.77, speed: Float? = null, accuracy: Float = 6f, altitude: Double? = 812.0) =
        RawLocationSample(lat, 35.21, accuracy, capturedAt, altitudeMeters = altitude, speedMetersPerSecond = speed)

    /** An ACTIVE trip that started ten minutes ago, being recorded. */
    private suspend fun recordingTrip(): Long {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = now - 600,
                endEpochSecond = null,
                startZoneId = "Asia/Jerusalem",
                state = TripState.ACTIVE.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = now - 600,
            ),
        )
        coordinator.start(tripId)
        withTimeout(5_000) { while (locationClient.activeSubscriptionCount == 0) delay(5) }
        return tripId
    }

    /** Delivers [batch] as the recording's next fixes and waits until the coordinator has seen them. */
    private suspend fun deliver(vararg batch: RawLocationSample) {
        locationClient.emit(batch.toList())
        val newest = batch.filter { it.accuracyMeters <= 50f }.maxByOrNull { it.capturedAtEpochSecond } ?: return
        withTimeout(5_000) { coordinator.latestFix.first { it == newest } }
    }

    @Test
    fun `a recent fix marks the spot at once, numbered from 1`() = runBlocking {
        val tripId = recordingTrip()
        deliver(fix(now - 20, lat = 31.7701, speed = 1.2f))
        val marker = marker()

        assertEquals(SpotMarkResult.Marked(1), marker.markSpot())
        deliver(fix(now - 2, lat = 31.7703, speed = 1.2f))
        assertEquals(SpotMarkResult.Marked(2), marker.markSpot())

        val spots = repository.getTripSpots(tripId)
        assertEquals(listOf(1, 2), spots.map { it.number })
        val first = spots.first()
        assertEquals(31.7701, first.latitude, 0.0)
        assertEquals(35.21, first.longitude, 0.0)
        assertEquals(now - 20, first.capturedAtEpochSecond)
        assertEquals(812.0, first.altitudeMeters!!, 0.0)
        assertNull(first.name)
        assertEquals("no fresh fix needed", 0, locationClient.currentLocationRequests)
    }

    @Test
    fun `standing still with no recent fix, a fresh fix is asked for and used`() = runBlocking {
        val tripId = recordingTrip()
        deliver(fix(now - 120, lat = 31.70))
        locationClient.currentLocationAnswer = { fix(now + 1, lat = 31.7705, speed = 0f) }

        assertEquals(SpotMarkResult.Marked(1), marker().markSpot())

        assertEquals(1, locationClient.currentLocationRequests)
        assertEquals(31.7705, repository.getTripSpots(tripId).single().latitude, 0.0)
    }

    @Test
    fun `in a moving jeep a closer fix is waited for rather than one from 300 m back`() = runBlocking {
        val tripId = recordingTrip()
        val fortyKmh = 40f / 3.6f
        // The newest fix, 25 s before the tap: about 280 m back down the track.
        deliver(fix(now - 25, lat = 31.7600, speed = fortyKmh))
        val fresh = CompletableDeferred<RawLocationSample?>()
        locationClient.currentLocationAnswer = { fresh.await() }

        val result = scope.async { marker(waitMillis = 5_000).markSpot() }
        withTimeout(5_000) { while (locationClient.currentLocationRequests == 0) delay(5) }
        fresh.complete(fix(now + 1, lat = 31.7625, speed = fortyKmh))

        assertEquals(SpotMarkResult.Marked(1), result.await())
        assertEquals(31.7625, repository.getTripSpots(tripId).single().latitude, 0.0)
    }

    @Test
    fun `the recording's own next fix ends the wait when it is close enough`() = runBlocking {
        val tripId = recordingTrip()
        deliver(fix(now - 60))
        locationClient.currentLocationAnswer = { awaitCancellation() }

        val result = scope.async { marker(waitMillis = 5_000).markSpot() }
        withTimeout(5_000) { while (locationClient.currentLocationRequests == 0) delay(5) }
        deliver(fix(now - 2, lat = 31.7712, speed = 1.1f))

        assertEquals(SpotMarkResult.Marked(1), result.await())
        assertEquals(31.7712, repository.getTripSpots(tripId).single().latitude, 0.0)
    }

    @Test
    fun `when no closer fix comes, the newest from the last 30 s is used`() = runBlocking {
        val tripId = recordingTrip()
        deliver(fix(now - 12, lat = 31.7650, speed = 40f / 3.6f))

        assertEquals(SpotMarkResult.Marked(1), marker(waitMillis = 200).markSpot())

        assertEquals(1, locationClient.currentLocationRequests)
        assertEquals(31.7650, repository.getTripSpots(tripId).single().latitude, 0.0)
    }

    @Test
    fun `with no usable fix nothing is saved and the result says so`() = runBlocking {
        val tripId = recordingTrip()
        // Too old, too inaccurate, and a fresh fix that never comes in time.
        deliver(fix(now - 45))
        locationClient.emit(listOf(fix(now - 1, accuracy = 120f)))
        locationClient.currentLocationAnswer = { fix(now, accuracy = 90f) }

        assertEquals(SpotMarkResult.NoFix, marker(waitMillis = 200).markSpot())
        assertTrue(repository.getTripSpots(tripId).isEmpty())
    }

    @Test
    fun `a cached fix from before the trip started is never a spot`() = runBlocking {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = now - 5,
                endEpochSecond = null,
                startZoneId = "UTC",
                state = TripState.ACTIVE.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = now - 5,
            ),
        )
        coordinator.start(tripId)
        withTimeout(5_000) { while (locationClient.activeSubscriptionCount == 0) delay(5) }
        deliver(fix(now - 10))

        assertEquals(SpotMarkResult.NoFix, marker(waitMillis = 200).markSpot())
    }

    @Test
    fun `nothing is marked when no trip is being recorded`() = runBlocking {
        assertEquals(SpotMarkResult.NotRecording, marker().markSpot())

        // An ACTIVE row whose recording isn't running in this process isn't "actively recording" either.
        repository.startTrip()
        assertEquals(SpotMarkResult.NotRecording, marker().markSpot())
    }

    @Test
    fun `a trip that finishes while the marker waits for a fix gets no spot`() = runBlocking {
        val tripId = recordingTrip()
        val fresh = CompletableDeferred<RawLocationSample?>()
        locationClient.currentLocationAnswer = { fresh.await() }

        val result = scope.async { marker(waitMillis = 5_000).markSpot() }
        withTimeout(5_000) { while (locationClient.currentLocationRequests == 0) delay(5) }
        repository.finishTrip(tripId, now)
        fresh.complete(fix(now))

        assertEquals(SpotMarkResult.NotRecording, result.await())
        assertTrue(repository.getTripSpots(tripId).isEmpty())
    }

    @Test
    fun `the newest fix counts even when the route drops it, but never an unusable or older one`() = runBlocking {
        val tripId = recordingTrip()
        deliver(fix(now - 10))
        // Too inaccurate for anything, older than the newest, and too inaccurate for the route (30 m) but fine for a spot.
        val droppedByRoute = fix(now - 3, lat = 31.78, accuracy = 40f)
        deliver(fix(now - 5, accuracy = 200f), fix(now - 30), droppedByRoute)

        assertEquals(droppedByRoute, coordinator.latestFix.value)
        assertEquals(1, repository.getTripPoints(tripId).size)
        assertEquals(SpotMarkResult.Marked(1), marker().markSpot())
        assertEquals(31.78, repository.getTripSpots(tripId).single().latitude, 0.0)

        coordinator.stop()
        assertNull(coordinator.latestFix.value)
        assertNull(coordinator.recordingTripId)
    }

    // ---- One fix, one spot ----

    @Test
    fun `a second tap that ends on the same fix marks nothing new`() = runBlocking {
        val tripId = recordingTrip()
        deliver(fix(now - 20, lat = 31.7701, speed = 1.2f))
        val marker = marker()

        assertEquals(SpotMarkResult.Marked(1), marker.markSpot())
        assertEquals(SpotMarkResult.AlreadyMarked(1), marker.markSpot())

        assertEquals(1, repository.getTripSpots(tripId).size)
    }

    /**
     * In a jeep the first tap waits for a fresh fix, one the recording never sees. A bump a moment
     * later must not ask for another one, ten metres on, and save a second spot.
     */
    @Test
    fun `a bump right after a mark in a moving jeep marks nothing new`() = runBlocking {
        val tripId = recordingTrip()
        val fortyKmh = 40f / 3.6f
        deliver(fix(now - 25, lat = 31.7600, speed = fortyKmh))
        val answers = ArrayDeque(listOf(fix(now + 1, lat = 31.7625, speed = fortyKmh), fix(now + 2, lat = 31.7626, speed = fortyKmh)))
        locationClient.currentLocationAnswer = { synchronized(answers) { answers.removeFirstOrNull() } }
        val marker = marker(waitMillis = 2_000)

        assertEquals(SpotMarkResult.Marked(1), marker.markSpot())
        assertEquals(SpotMarkResult.AlreadyMarked(1), marker.markSpot())

        assertEquals(1, repository.getTripSpots(tripId).size)
        assertEquals(31.7625, repository.getTripSpots(tripId).single().latitude, 0.0)
        assertEquals("no second fresh fix asked for", 1, locationClient.currentLocationRequests)
    }

    /** The notification's Mark spot tapped twice while the first tap still waits for its fix. */
    @Test
    fun `two taps at once in a moving jeep mark one spot`() = runBlocking {
        val tripId = recordingTrip()
        val fortyKmh = 40f / 3.6f
        deliver(fix(now - 25, lat = 31.7600, speed = fortyKmh))
        val answers = ArrayDeque(listOf(fix(now + 1, lat = 31.7625, speed = fortyKmh), fix(now + 2, lat = 31.7626, speed = fortyKmh)))
        locationClient.currentLocationAnswer = { delay(100); synchronized(answers) { answers.removeFirstOrNull() } }
        val marker = marker(waitMillis = 2_000)

        val first = scope.async { marker.markSpot() }
        val second = scope.async { marker.markSpot() }

        assertEquals(setOf(SpotMarkResult.Marked(1), SpotMarkResult.AlreadyMarked(1)), setOf(first.await(), second.await()))
        assertEquals(1, repository.getTripSpots(tripId).size)
    }

    @Test
    fun `a deliberate second mark after moving on is a new spot`() = runBlocking {
        val tripId = recordingTrip()
        val fortyKmh = 40f / 3.6f
        deliver(fix(now - 1, lat = 31.7600, speed = fortyKmh))
        val clock = MutableClock(now)
        val marker = TripSpotMarker(repository, coordinator, locationClient, clock, scope, waitForFixMillis = 2_000)
        assertEquals(SpotMarkResult.Marked(1), marker.markSpot())

        // Ten seconds on: about 110 m further, and the recording has no newer fix yet.
        clock.epochSecond = now + 10
        locationClient.currentLocationAnswer = { fix(now + 11, lat = 31.7610, speed = fortyKmh) }
        assertEquals(SpotMarkResult.Marked(2), marker.markSpot())

        assertEquals(listOf(31.7600, 31.7610), repository.getTripSpots(tripId).map { it.latitude })
    }

    private class MutableClock(@Volatile var epochSecond: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochSecond(epochSecond)
    }
}
