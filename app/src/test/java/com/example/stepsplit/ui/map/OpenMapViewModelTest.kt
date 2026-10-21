package com.example.stepsplit.ui.map

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.data.local.trip.TripSpotEntity
import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.data.trip.MapLocationClient
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Open map's two promises: it shows the trip being recorded exactly as the rest of the app does
 * (the same cleaned-up, smoothed route), and it asks for location only while the screen collects
 * [OpenMapViewModel.location] *and* has said location is available - never otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenMapViewModelTest {

    private lateinit var database: StepSplitDatabase
    private val fixedNow = Instant.parse("2026-03-10T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `with no trip recording there is nothing to draw but the map`() = runTest {
        insertTrip(TripState.FINISHED, pointCount = 5)
        val viewModel = OpenMapViewModel(TripRepository(database, clock), FakeMapLocationClient())

        val state = viewModel.uiState.first { !it.isLoading }

        assertNull(state.recording)
    }

    @Test
    fun `the trip being recorded is drawn with the same smoothed route and spots the rest of the app shows`() = runTest {
        insertTrip(TripState.FINISHED, pointCount = 3)
        val activeId = insertTrip(TripState.ACTIVE, pointCount = 6)
        database.tripSpotDao().insert(
            TripSpotEntity(tripId = activeId, number = 1, capturedAtEpochSecond = fixedNow.epochSecond + 20, latitude = 31.0002, longitude = 35.0, altitudeMeters = null),
        )
        val viewModel = OpenMapViewModel(TripRepository(database, clock), FakeMapLocationClient())

        val recording = viewModel.uiState.first { it.recording?.spots?.isNotEmpty() == true }.recording!!

        val expected = RouteSmoother.smooth(RouteSanitizer.sanitize(pointsFor(activeId, 6)).points)
        assertEquals(expected.points, recording.route)
        assertEquals(expected.distanceMeters, recording.distanceMeters, 1e-9)
        assertEquals(listOf(1), recording.spots.map { it.number })
    }

    @Test
    fun `location is asked for only while the screen collects it and location is available`() = runTest {
        val client = FakeMapLocationClient(last = fix(1))
        val viewModel = OpenMapViewModel(TripRepository(database, clock), client)

        // Location available, but nothing collects yet: no request.
        viewModel.setLocationAvailable(true)
        advanceUntilIdle()
        assertEquals(0, client.requests.get())

        // The screen collects, but hasn't said location is available: no request.
        viewModel.setLocationAvailable(false)
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.location.collect {} }
        advanceUntilIdle()
        assertEquals(0, client.requests.get())
        assertNull(viewModel.location.value)

        // Both: the last known fix at once, then live ones.
        viewModel.setLocationAvailable(true)
        advanceUntilIdle()
        assertEquals(1, client.activeRequests.get())
        assertEquals(fix(1), viewModel.location.value)
        client.send(fix(2))
        advanceUntilIdle()
        assertEquals(fix(2), viewModel.location.value)

        // The screen goes away (or the app to the background): the request stops at once.
        collecting.cancel()
        advanceUntilIdle()
        assertEquals(0, client.activeRequests.get())
    }

    @Test
    fun `turning location off stops the request but keeps the last fix to show grayed`() = runTest {
        val client = FakeMapLocationClient()
        val viewModel = OpenMapViewModel(TripRepository(database, clock), client)
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.location.collect {} }
        viewModel.setLocationAvailable(true)
        advanceUntilIdle()
        client.send(fix(3))
        advanceUntilIdle()

        viewModel.setLocationAvailable(false)
        advanceUntilIdle()

        assertEquals(0, client.activeRequests.get())
        assertEquals(fix(3), viewModel.location.value)

        // Location back on: a new request.
        viewModel.setLocationAvailable(true)
        advanceUntilIdle()
        assertEquals(1, client.activeRequests.get())
        assertEquals(2, client.requests.get())
        collecting.cancel()
    }

    @Test
    fun `a refused location request leaves the map without a dot instead of failing`() = runTest {
        val client = FakeMapLocationClient(refusal = SecurityException("no permission"))
        val viewModel = OpenMapViewModel(TripRepository(database, clock), client)
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.location.collect {} }

        viewModel.setLocationAvailable(true)
        advanceUntilIdle()

        assertNull(viewModel.location.value)
        assertEquals(0, client.activeRequests.get())
        collecting.cancel()
    }

    @Test
    fun `a fix counts as current for 30 seconds`() {
        val takenAt = 5_000_000_000L
        val fix = fix(1).copy(elapsedRealtimeNanos = takenAt)

        assertEquals(FRESH_FIX_MILLIS, remainingFreshMillis(fix, nowElapsedRealtimeNanos = takenAt))
        assertEquals(20_000L, remainingFreshMillis(fix, nowElapsedRealtimeNanos = takenAt + 10_000_000_000L))
        assertEquals(0L, remainingFreshMillis(fix, nowElapsedRealtimeNanos = takenAt + 30_000_000_000L))
        assertEquals(0L, remainingFreshMillis(fix, nowElapsedRealtimeNanos = takenAt + 3_600_000_000_000L))
        assertEquals(0L, remainingFreshMillis(null, nowElapsedRealtimeNanos = takenAt))
        // A fix stamped a moment after "now" (two clocks read in a different order) is simply new.
        assertEquals(FRESH_FIX_MILLIS, remainingFreshMillis(fix, nowElapsedRealtimeNanos = takenAt - 1_000_000L))
    }

    private suspend fun insertTrip(state: TripState, pointCount: Int): Long {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond,
                endEpochSecond = if (state == TripState.FINISHED) fixedNow.epochSecond + 600 else null,
                startZoneId = "UTC",
                state = state.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = fixedNow.epochSecond + (pointCount - 1) * 10L,
                createdAtEpochSecond = fixedNow.epochSecond,
            ),
        )
        pointsFor(tripId, pointCount).forEach { point ->
            database.tripPointDao().insert(
                TripPointEntity(
                    tripId = tripId,
                    capturedAtEpochSecond = point.capturedAtEpochSecond,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    accuracyMeters = point.accuracyMeters,
                    altitudeMeters = point.altitudeMeters,
                    speedMetersPerSecond = point.speedMetersPerSecond,
                ),
            )
        }
        return tripId
    }

    /** A walk north, about 14 m every 10 seconds, wobbling a few meters sideways - a route the smoother changes. */
    private fun pointsFor(tripId: Long, count: Int): List<TripPoint> = (0 until count).map { i ->
        TripPoint(
            capturedAtEpochSecond = fixedNow.epochSecond + i * 10L,
            latitude = 31.0 + tripId * 0.01 + i * 0.000126,
            longitude = 35.0 + if (i % 2 == 0) 0.00004 else -0.00004,
            accuracyMeters = 8f,
            altitudeMeters = null,
            speedMetersPerSecond = 1.4f,
        )
    }

    private fun fix(n: Int) = MapFix(latitude = 31.0 + n * 0.001, longitude = 35.0, accuracyMeters = 5f, bearingDegrees = null, elapsedRealtimeNanos = n * 1_000_000_000L)

    /** Counts requests the way the real client makes them: one per collection of [locationUpdates]. */
    private class FakeMapLocationClient(
        private val last: MapFix? = null,
        private val refusal: Exception? = null,
    ) : MapLocationClient {
        val requests = AtomicInteger(0)
        val activeRequests = AtomicInteger(0)
        private val live = MutableSharedFlow<MapFix>()

        override suspend fun lastLocation(): MapFix? = last

        override fun locationUpdates(): Flow<MapFix> = flow {
            refusal?.let { throw it }
            requests.incrementAndGet()
            activeRequests.incrementAndGet()
            try {
                emitAll(live)
            } finally {
                activeRequests.decrementAndGet()
            }
        }

        suspend fun send(fix: MapFix) = live.emit(fix)
    }
}
