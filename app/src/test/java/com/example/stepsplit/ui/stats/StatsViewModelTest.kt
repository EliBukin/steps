package com.example.stepsplit.ui.stats

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.DeletedTripTotalsEntity
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripState
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

        val off = viewModel.uiState.first { !it.isLoading && it.trips.hasTrips }.trips
        assertEquals(1, off.tripCount)
        assertEquals(0, off.deletedTripCount)
        assertEquals(600L, off.totalDurationSeconds)

        keep.value = true
        val on = viewModel.uiState.first { it.trips.tripCount == 2 }.trips
        assertEquals(1, on.deletedTripCount)
        assertEquals(1_500L, on.totalDurationSeconds)
        assertEquals(2_000.0, on.totalDistanceMeters, 1e-9)

        keep.value = false
        assertEquals(0, viewModel.uiState.first { it.trips.tripCount == 1 }.trips.deletedTripCount)
    }
}
