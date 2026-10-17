package com.example.stepsplit.ui.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.DeletedTripTotalsEntity
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var stepRepository: StepRepository
    private lateinit var tripRepository: TripRepository
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        stepRepository = StepRepository(database, FakeStepSource(), SettingsRepository(context), clock, CoroutineScope(StandardTestDispatcher()))
        tripRepository = TripRepository(database, clock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `trips deleted with Keep in Stats are counted only while the setting is on`() = runTest {
        // One finished trip with no route (0 m), and one deleted trip kept in Stats.
        database.tripDao().insert(
            TripEntity(
                startEpochSecond = 1_000L,
                endEpochSecond = 1_600L,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = 1_000L,
            ),
        )
        database.deletedTripTotalsDao().insert(DeletedTripTotalsEntity(startEpochSecond = 500L, durationSeconds = 900L, distanceMeters = 2_000.0))
        val keep = MutableStateFlow(false)
        val viewModel = StatsViewModel(stepRepository, tripRepository, keep)

        val off = viewModel.uiState.first { !it.isLoading && it.trips.hasTrips }.trips.walks
        assertEquals(1, off.tripCount)
        assertEquals(0, off.deletedTripCount)
        assertEquals(600L, off.totalDurationSeconds)

        keep.value = true
        val on = viewModel.uiState.first { it.trips.walks.tripCount == 2 }.trips.walks
        assertEquals(1, on.deletedTripCount)
        assertEquals(1_500L, on.totalDurationSeconds)
        assertEquals(2_000.0, on.totalDistanceMeters, 1e-9)

        keep.value = false
        assertEquals(0, viewModel.uiState.first { it.trips.walks.tripCount == 1 }.trips.walks.deletedTripCount)
    }

    @Test
    fun `walks and drives are counted apart, by the owner's choice or else the suggestion`() = runTest {
        val start = 10_000L
        // A 20-minute drive at 36 km/h, a point every 5 s: suggested as a drive.
        val driveId = finishedTrip(start, durationSeconds = 1_200)
        insertStraightRoute(driveId, start, points = 241, everySeconds = 5, metersEach = 50.0, speed = 10f)
        // A 40-minute walk at 4.3 km/h: suggested as a walk, but the owner says it was a drive.
        val walkCalledDriveId = finishedTrip(start + 5_000, durationSeconds = 2_400)
        insertStraightRoute(walkCalledDriveId, start + 5_000, points = 241, everySeconds = 10, metersEach = 12.0, speed = 1.2f)
        tripRepository.updateTripDetails(walkCalledDriveId, name = null, typeOverride = TripType.DRIVE)
        // A plain walk, and a kept deleted drive.
        val walkId = finishedTrip(start + 9_000, durationSeconds = 2_400)
        insertStraightRoute(walkId, start + 9_000, points = 241, everySeconds = 10, metersEach = 12.0, speed = 1.2f)
        database.deletedTripTotalsDao().insert(
            DeletedTripTotalsEntity(startEpochSecond = 500L, durationSeconds = 1_800L, distanceMeters = 15_000.0, type = TripType.DRIVE.name),
        )
        val viewModel = StatsViewModel(stepRepository, tripRepository, MutableStateFlow(true))

        val trips = viewModel.uiState.first { !it.isLoading && it.trips.hasTrips }.trips

        assertEquals(1, trips.walks.tripCount)
        assertEquals(2_400L, trips.walks.totalDurationSeconds)
        assertEquals(3, trips.drives.tripCount)
        assertEquals(1, trips.drives.deletedTripCount)
        assertEquals(1_200L + 2_400L + 1_800L, trips.drives.totalDurationSeconds)
    }

    private suspend fun finishedTrip(startEpochSecond: Long, durationSeconds: Long): Long = database.tripDao().insert(
        TripEntity(
            startEpochSecond = startEpochSecond,
            endEpochSecond = startEpochSecond + durationSeconds,
            startZoneId = "UTC",
            state = TripState.FINISHED.name,
            distanceMeters = 0.0,
            lastAcceptedPointEpochSecond = startEpochSecond + durationSeconds,
            createdAtEpochSecond = startEpochSecond,
        ),
    )

    /** [points] points heading north, [metersEach] apart and [everySeconds] apart, each reporting [speed] m/s. */
    private suspend fun insertStraightRoute(tripId: Long, start: Long, points: Int, everySeconds: Long, metersEach: Double, speed: Float) {
        repeat(points) { i ->
            database.tripPointDao().insert(
                TripPointEntity(
                    tripId = tripId,
                    capturedAtEpochSecond = start + i * everySeconds,
                    latitude = 31.0 + i * metersEach / 111_195.0,
                    longitude = 35.0,
                    accuracyMeters = 5f,
                    altitudeMeters = null,
                    speedMetersPerSecond = speed,
                ),
            )
        }
    }
}
