package com.example.stepsplit.architecture

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.repository.StepRepository
import com.example.stepsplit.data.settings.SettingsRepository
import com.example.stepsplit.data.stepsource.FakeStepSource
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behavioral proof that automatic step counting and manually started GPS trip recording, though
 * sharing one physical Room database, are completely independent - see
 * [ArchitectureInvariantsTest] for the static (import-scanning) half of this same product
 * requirement. Both repositories are constructed here against the SAME database instance, exactly
 * like [com.example.stepsplit.di.AppContainer] does in production, specifically so an accidental
 * cross-feature read or write would actually be visible to these tests.
 */
@RunWith(RobolectricTestRunner::class)
class StepGpsSeparationTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var fakeSource: FakeStepSource
    private lateinit var stepRepository: StepRepository
    private lateinit var tripRepository: TripRepository

    private val fixedNow = Instant.parse("2026-08-15T12:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val repositoryScope = CoroutineScope(Dispatchers.Unconfined)
    private val baseEpoch = fixedNow.epochSecond - 3600

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        settingsRepository = SettingsRepository(context)
        fakeSource = FakeStepSource()
        stepRepository = StepRepository(database, fakeSource, settingsRepository, clock, repositoryScope)
        tripRepository = TripRepository(database, clock)
    }

    @Test
    fun `step collection availability is unaffected by an active trip`() = runTest {
        // Nothing here ever grants/denies a location permission - HealthConnectStepSource's own
        // checkAvailability has no location-permission input to begin with (see its own doc
        // comment); this pins that down against a future regression rather than only asserting it
        // by code review.
        val availabilityBefore = stepRepository.checkAvailability()
        tripRepository.startTrip()
        assertEquals(availabilityBefore, stepRepository.checkAvailability())
    }

    @Test
    fun `starting, finishing, and deleting a trip leaves all step rows and totals unchanged`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        stepRepository.syncNow()
        val today = LocalDate.ofInstant(fixedNow, ZoneOffset.UTC)
        val totalsBefore = stepRepository.observeLifetimeStats().first()
        val breakdownBefore = stepRepository.observeDailyBreakdowns(listOf(today)).first()
        val bucketRowCountBefore = database.stepBucketDao().count()

        val tripId = tripRepository.startTrip()
        tripRepository.finishTrip(tripId, fixedNow.epochSecond + 600)
        tripRepository.deleteTrip(tripId)

        assertEquals(totalsBefore, stepRepository.observeLifetimeStats().first())
        assertEquals(breakdownBefore, stepRepository.observeDailyBreakdowns(listOf(today)).first())
        assertEquals(bucketRowCountBefore, database.stepBucketDao().count())
    }

    @Test
    fun `an interrupted trip also leaves step totals unchanged`() = runTest {
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 50)
        stepRepository.syncNow()
        val totalsBefore = stepRepository.observeLifetimeStats().first()

        // Bypasses the ownership/claim machinery deliberately - only the resulting persisted
        // state (an INTERRUPTED trip row) matters for this test, not how a real interruption gets
        // there (already covered by TripRepositoryTest).
        database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond,
                endEpochSecond = null,
                startZoneId = "UTC",
                state = TripState.INTERRUPTED.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = fixedNow.epochSecond,
            ),
        )

        assertEquals(totalsBefore, stepRepository.observeLifetimeStats().first())
    }

    @Test
    fun `a step import while a trip is active behaves exactly like a step import with no trip`() = runTest {
        tripRepository.startTrip()
        fakeSource.addInterval(baseEpoch, baseEpoch + 60, 42)
        val resultWithActiveTrip = stepRepository.syncNow()
        val bucketsWithActiveTrip = database.stepBucketDao().getAllActive().map { it.startEpochSecond to it.steps }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controlDatabase = Room.inMemoryDatabaseBuilder(context, StepSplitDatabase::class.java).build()
        val controlSource = FakeStepSource().apply { addInterval(baseEpoch, baseEpoch + 60, 42) }
        val controlRepository = StepRepository(controlDatabase, controlSource, SettingsRepository(context), clock, repositoryScope)
        val resultNoTrip = controlRepository.syncNow()
        val bucketsNoTrip = controlDatabase.stepBucketDao().getAllActive().map { it.startEpochSecond to it.steps }

        assertEquals(resultNoTrip, resultWithActiveTrip)
        assertEquals(bucketsNoTrip, bucketsWithActiveTrip)
    }
}
