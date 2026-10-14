package com.example.stepsplit.ui.trips

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RouteMath
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the Trips list shares [TripDetailViewModel]'s exact same
 * `RouteSanitizer.sanitize().points -> RouteSmoother.smooth()` pipeline for a finished trip's
 * distance - see [TripDetailViewModel]'s own doc comment for why this must never disagree with
 * what tapping into that trip's detail then shows. Points are seeded directly through
 * [com.example.stepsplit.data.local.trip.TripPointDao], bypassing
 * [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy] entirely, exactly like
 * [TripDetailViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripsViewModelTest {

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
        Dispatchers.resetMain()
    }

    @Test
    fun `the finished-trip list distance matches the exact same sanitize-then-smooth pipeline the detail screen uses`() = runTest {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond,
                endEpochSecond = fixedNow.epochSecond + 40,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                // The stale, outlier-inflated distance a pre-fix live recording would have stored -
                // the list must never surface this raw value either.
                distanceMeters = 999_999.0,
                lastAcceptedPointEpochSecond = fixedNow.epochSecond + 40,
                createdAtEpochSecond = fixedNow.epochSecond,
            ),
        )

        // The same individually-plausible, laterally-zig-zagging fixture TripDetailViewModelTest
        // uses - nothing here is rejected by the sanitizer, but the raw path is measurably longer
        // than the true straight-line distance.
        val earthRadiusMeters = 6_371_000.0
        val trueStepMeters = 14.0
        val lateralJitterMeters = 4.0
        val rawEntities = (0 until 5).map { i ->
            val lateralSign = if (i % 2 == 0) 1.0 else -1.0
            TripPointEntity(
                tripId = tripId,
                capturedAtEpochSecond = fixedNow.epochSecond + i * 10L,
                latitude = 10.0 + Math.toDegrees((trueStepMeters * i) / earthRadiusMeters),
                longitude = 20.0 + Math.toDegrees((lateralSign * lateralJitterMeters) / (earthRadiusMeters * Math.cos(Math.toRadians(10.0)))),
                accuracyMeters = 10f,
                altitudeMeters = null,
                speedMetersPerSecond = 1.4f,
            )
        }
        rawEntities.forEach { database.tripPointDao().insert(it) }

        val repository = TripRepository(database, clock)
        val viewModel = TripsViewModel(repository, clock)

        val uiState = viewModel.uiState.first { it.history.isNotEmpty() }

        val listedTrip = uiState.history.single()
        assertEquals(tripId, listedTrip.id)
        assertTrue(listedTrip.distanceMeters < 999_999.0)

        val rawDomainPoints = rawEntities.map {
            TripPoint(it.capturedAtEpochSecond, it.latitude, it.longitude, it.accuracyMeters, it.altitudeMeters, it.speedMetersPerSecond)
        }
        val independentlyProcessed = RouteSmoother.smooth(RouteSanitizer.sanitize(rawDomainPoints).points)
        assertEquals(independentlyProcessed.distanceMeters, listedTrip.distanceMeters, 1e-9)

        var rawDistance = 0.0
        for (i in 1 until rawEntities.size) {
            rawDistance += RouteMath.haversineMeters(rawEntities[i - 1].latitude, rawEntities[i - 1].longitude, rawEntities[i].latitude, rawEntities[i].longitude)
        }
        assertTrue(
            "the list distance (${listedTrip.distanceMeters}) must reflect the same wobble reduction as detail, not the raw zig-zag distance ($rawDistance)",
            listedTrip.distanceMeters < rawDistance,
        )
    }

    private fun tripEntity(startEpochSecond: Long, state: TripState) = TripEntity(
        startEpochSecond = startEpochSecond,
        endEpochSecond = if (state == TripState.FINISHED) startEpochSecond + 600 else null,
        startZoneId = "UTC",
        state = state.name,
        distanceMeters = 0.0,
        lastAcceptedPointEpochSecond = null,
        createdAtEpochSecond = startEpochSecond,
    )

    private fun pointEntity(tripId: Long, capturedAtEpochSecond: Long, index: Int) = TripPointEntity(
        tripId = tripId,
        capturedAtEpochSecond = capturedAtEpochSecond,
        latitude = 10.0 + index * 0.0001,
        longitude = 20.0,
        accuracyMeters = 10f,
        altitudeMeters = null,
        speedMetersPerSecond = 1.4f,
    )

    @Test
    fun `point writes for another trip never recompute an already-finished trip's distance`() = runTest {
        val finishedStart = fixedNow.epochSecond - 7200
        val finishedId = database.tripDao().insert(tripEntity(finishedStart, TripState.FINISHED))
        (0 until 5).forEach { database.tripPointDao().insert(pointEntity(finishedId, finishedStart + it * 10L, it)) }
        // Recording into this trip is what used to re-smooth every finished trip on each fix:
        // Room invalidates observers per table, so any trip_points write reached all of them.
        val recordingStart = fixedNow.epochSecond - 600
        val recordingId = database.tripDao().insert(tripEntity(recordingStart, TripState.ACTIVE))

        val finishedTripComputations = AtomicInteger()
        val repository = TripRepository(database, clock)
        val viewModel = TripsViewModel(repository, clock) { points ->
            if (points.firstOrNull()?.capturedAtEpochSecond == finishedStart) finishedTripComputations.incrementAndGet()
            RouteSmoother.smooth(RouteSanitizer.sanitize(points).points).distanceMeters
        }
        viewModel.uiState.first { it.history.size == 1 }

        (0 until 5).forEach { database.tripPointDao().insert(pointEntity(recordingId, recordingStart + it * 10L, it)) }
        // A second trip finishing is a positive signal that the history has been re-derived since.
        val laterStart = fixedNow.epochSecond - 3600
        database.tripDao().insert(tripEntity(laterStart, TripState.FINISHED))
        viewModel.uiState.first { it.history.size == 2 }

        assertEquals(1, finishedTripComputations.get())
    }
}
