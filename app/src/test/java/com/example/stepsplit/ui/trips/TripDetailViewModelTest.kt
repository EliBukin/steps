package com.example.stepsplit.ui.trips

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.data.local.trip.TripSpotEntity
import com.example.stepsplit.data.trip.SeaLevelHeights
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.model.TripType
import com.example.stepsplit.domain.trip.RouteMath
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import com.example.stepsplit.domain.trip.RouteSpeedColoring
import com.example.stepsplit.domain.trip.TripElevation
import com.example.stepsplit.domain.trip.TripMotion
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves [TripDetailViewModel]'s central architectural guarantee: the map, GPX export, and
 * displayed distance can never disagree, because they are all driven by the exact same
 * `RouteSanitizer.sanitize().points -> RouteSmoother.smooth()` output computed once per
 * points-flow emission - see that ViewModel's own doc comment. Points are seeded directly through
 * [com.example.stepsplit.data.local.trip.TripPointDao], bypassing
 * [com.example.stepsplit.domain.trip.RoutePointAcceptancePolicy] entirely, to simulate an
 * already-stored trip whose points predate the strengthened policy - exactly the real-world case
 * this pipeline exists to fix, since that data can never be re-collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TripDetailViewModelTest {

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
    fun `map points, GPX export, and displayed distance all derive from the identical sanitized route`() = runTest {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond,
                endEpochSecond = fixedNow.epochSecond + 1_200,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                // The stale, outlier-inflated distance a pre-fix live recording would have stored -
                // the ViewModel must never surface this raw value.
                distanceMeters = 999_999.0,
                lastAcceptedPointEpochSecond = fixedNow.epochSecond + 20,
                createdAtEpochSecond = fixedNow.epochSecond,
            ),
        )

        val earthRadiusMeters = 6_371_000.0
        val goodA = TripPointEntity(
            tripId = tripId,
            capturedAtEpochSecond = fixedNow.epochSecond,
            latitude = 10.0,
            longitude = 20.0,
            accuracyMeters = 10f,
            altitudeMeters = null,
            speedMetersPerSecond = 1.4f,
        )
        val spike = TripPointEntity(
            tripId = tripId,
            capturedAtEpochSecond = fixedNow.epochSecond + 10,
            latitude = 10.0 + Math.toDegrees(300.0 / earthRadiusMeters),
            longitude = 20.0,
            accuracyMeters = 12f,
            altitudeMeters = null,
            speedMetersPerSecond = 1.5f,
        )
        val goodC = TripPointEntity(
            tripId = tripId,
            capturedAtEpochSecond = fixedNow.epochSecond + 20,
            latitude = 10.0 + Math.toDegrees(14.0 / earthRadiusMeters),
            longitude = 20.0,
            accuracyMeters = 10f,
            altitudeMeters = null,
            speedMetersPerSecond = 1.4f,
        )
        database.tripPointDao().insert(goodA)
        database.tripPointDao().insert(spike)
        database.tripPointDao().insert(goodC)

        val repository = TripRepository(database, clock)
        val viewModel = TripDetailViewModel(repository, tripId, keepDeletedTripsInStats = flowOf(false), seaLevelHeights = StoredHeightsOnly)

        val uiState = viewModel.uiState.first { !it.isLoading }

        // The spike is gone from what the map (and, by construction, GPX export) both read.
        assertEquals(2, uiState.points.size)
        assertTrue(uiState.points.none { it.latitude == spike.latitude })

        // The displayed distance is exactly the sanitize-then-smooth pipeline's own distance for
        // this trip's raw stored points - never the stale persisted value, and never independently
        // recomputed elsewhere. Recomputed here from the raw entities directly (not from
        // `uiState.points`, which is already the pipeline's *output*) so this is a genuine,
        // independent check of the whole production path, not a tautology against itself.
        val trip = uiState.trip!!
        val rawDomainPoints = listOf(goodA, spike, goodC).map {
            com.example.stepsplit.domain.model.TripPoint(
                capturedAtEpochSecond = it.capturedAtEpochSecond,
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyMeters = it.accuracyMeters,
                altitudeMeters = it.altitudeMeters,
                speedMetersPerSecond = it.speedMetersPerSecond,
            )
        }
        val independentlyProcessed = RouteSmoother.smooth(RouteSanitizer.sanitize(rawDomainPoints).points)
        assertEquals(independentlyProcessed.points, uiState.points)
        assertEquals(independentlyProcessed.distanceMeters, trip.distanceMeters, 1e-9)
        assertTrue(trip.distanceMeters < 999_999.0)

        // GPX export reads the exact same points list the map does (both are literally
        // `uiState.points`) - the spike's coordinate can never appear in it, and the surviving
        // points always do.
        val gpx = GpxExport.toGpx(uiState.points)
        assertTrue(gpx.contains(String.format(Locale.ROOT, "%.7f", goodA.latitude)))
        assertTrue(gpx.contains(String.format(Locale.ROOT, "%.7f", goodC.latitude)))
        assertTrue(!gpx.contains(String.format(Locale.ROOT, "%.7f", spike.latitude)))
    }

    @Test
    fun `RouteSmoother's wobble reduction reaches the map, GPX export, and displayed distance too, not just spike removal`() = runTest {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond,
                endEpochSecond = fixedNow.epochSecond + 40,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                distanceMeters = 999_999.0,
                lastAcceptedPointEpochSecond = fixedNow.epochSecond + 40,
                createdAtEpochSecond = fixedNow.epochSecond,
            ),
        )

        // Five individually-plausible points zig-zagging ~4m either side of a straight walking
        // line - nothing here would ever be rejected by RoutePointAcceptancePolicy/RouteSanitizer
        // (implied speed stays close to the reported 1.4 m/s throughout), yet the raw path is
        // measurably longer than the true straight-line distance - exactly the second real-world
        // defect (ordinary wobble, zero points rejected) RouteSmoother exists to reduce.
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
        val viewModel = TripDetailViewModel(repository, tripId, keepDeletedTripsInStats = flowOf(false), seaLevelHeights = StoredHeightsOnly)

        val uiState = viewModel.uiState.first { !it.isLoading }

        // Nothing was rejected - all five points are still present.
        assertEquals(5, uiState.points.size)

        // But at least one interior point's coordinates changed - proving the smoother actually
        // ran, not just the (here, no-op) sanitizer.
        val rawLongitudes = rawEntities.map { it.longitude }
        assertTrue(uiState.points.drop(1).dropLast(1).any { it.longitude !in rawLongitudes })

        // The displayed distance reflects that noise reduction: it is measurably less than the
        // raw stored path's own naive distance, and equals the pipeline's own independent output.
        var rawDistance = 0.0
        for (i in 1 until rawEntities.size) {
            rawDistance += RouteMath.haversineMeters(rawEntities[i - 1].latitude, rawEntities[i - 1].longitude, rawEntities[i].latitude, rawEntities[i].longitude)
        }
        val trip = uiState.trip!!
        assertTrue("smoothed distance (${trip.distanceMeters}) must be less than the raw zig-zag distance ($rawDistance)", trip.distanceMeters < rawDistance)
        val rawDomainPoints = rawEntities.map {
            com.example.stepsplit.domain.model.TripPoint(it.capturedAtEpochSecond, it.latitude, it.longitude, it.accuracyMeters, it.altitudeMeters, it.speedMetersPerSecond)
        }
        val independentlyProcessed = RouteSmoother.smooth(RouteSanitizer.sanitize(rawDomainPoints).points)
        assertEquals(independentlyProcessed.distanceMeters, trip.distanceMeters, 1e-9)

        // GPX export reflects the exact same (already-smoothed) coordinates the map/distance use -
        // point 1's raw longitude is deliberately checked here rather than an endpoint's, since
        // indices 0 and 4 share the same lateral sign as this fixture's other even indices and
        // would trivially "match" regardless of smoothing.
        val gpx = GpxExport.toGpx(uiState.points)
        assertTrue(gpx.contains(String.format(Locale.ROOT, "%.7f", uiState.points[1].latitude)))
        assertTrue(uiState.points[1].longitude != rawEntities[1].longitude)

        // The map's speed colors are drawn from those same displayed points, never the raw ones.
        assertEquals(RouteSpeedColoring.colorize(uiState.points), uiState.speedColoring)
        assertNotEquals(RouteSpeedColoring.colorize(rawDomainPoints), uiState.speedColoring)
    }

    /** A FINISHED trip with a short two-point route, inserted directly. */
    private suspend fun insertFinishedTrip(): Long {
        val start = fixedNow.epochSecond - 600
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = start,
                endEpochSecond = fixedNow.epochSecond,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = start + 20,
                createdAtEpochSecond = start,
            ),
        )
        for ((offset, latitude) in listOf(10L to 32.0, 20L to 32.0001)) {
            database.tripPointDao().insert(
                TripPointEntity(
                    tripId = tripId,
                    capturedAtEpochSecond = start + offset,
                    latitude = latitude,
                    longitude = 34.0,
                    accuracyMeters = 5f,
                    altitudeMeters = null,
                    speedMetersPerSecond = 1.1f,
                ),
            )
        }
        return tripId
    }

    @Test
    fun `the delete dialog follows the Keep deleted trips in Stats setting`() = runTest {
        val tripId = insertFinishedTrip()
        val keep = MutableStateFlow(false)
        val viewModel = TripDetailViewModel(TripRepository(database, clock), tripId, keep, StoredHeightsOnly)

        assertFalse(viewModel.uiState.first { !it.isLoading }.keepDeletedTripsInStats)
        keep.value = true
        assertTrue(viewModel.uiState.first { it.keepDeletedTripsInStats }.keepDeletedTripsInStats)
    }

    @Test
    fun `deleting with the setting on keeps the trip's time and distance for Stats`() = runTest {
        val tripId = insertFinishedTrip()
        val repository = TripRepository(database, clock)
        val viewModel = TripDetailViewModel(repository, tripId, keepDeletedTripsInStats = flowOf(true), seaLevelHeights = StoredHeightsOnly)

        viewModel.deleteTrip()

        assertTrue(viewModel.uiState.first { it.deleted }.deleted)
        assertEquals(600L, repository.observeDeletedTripTotals().first().single().durationSeconds)
    }

    @Test
    fun `deleting with the setting off keeps nothing`() = runTest {
        val tripId = insertFinishedTrip()
        val repository = TripRepository(database, clock)
        val viewModel = TripDetailViewModel(repository, tripId, keepDeletedTripsInStats = flowOf(false), seaLevelHeights = StoredHeightsOnly)

        viewModel.deleteTrip()

        assertTrue(viewModel.uiState.first { it.deleted }.deleted)
        assertTrue(repository.observeDeletedTripTotals().first().isEmpty())
    }

    /** A FINISHED 20-minute drive heading north at 36 km/h, a point every 5 s. */
    private suspend fun insertFinishedDrive(): Long {
        val start = fixedNow.epochSecond - 1_200
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = start,
                endEpochSecond = fixedNow.epochSecond,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = fixedNow.epochSecond,
                createdAtEpochSecond = start,
            ),
        )
        for (i in 0..240) {
            database.tripPointDao().insert(
                TripPointEntity(
                    tripId = tripId,
                    capturedAtEpochSecond = start + i * 5L,
                    latitude = 31.0 + i * 50.0 / 111_195.0,
                    longitude = 35.0,
                    accuracyMeters = 5f,
                    altitudeMeters = null,
                    speedMetersPerSecond = 10f,
                ),
            )
        }
        return tripId
    }

    @Test
    fun `the suggested type comes from the displayed route`() = runTest {
        val walk = TripDetailViewModel(TripRepository(database, clock), insertFinishedTrip(), flowOf(false), StoredHeightsOnly)
        val drive = TripDetailViewModel(TripRepository(database, clock), insertFinishedDrive(), flowOf(false), StoredHeightsOnly)

        val walkState = walk.uiState.first { !it.isLoading }
        val driveState = drive.uiState.first { !it.isLoading }

        assertEquals(TripType.WALK, walkState.suggestedType)
        assertEquals(TripType.WALK, walkState.type)
        assertEquals(TripType.DRIVE, driveState.suggestedType)
        assertEquals(TripType.DRIVE, driveState.type)
    }

    @Test
    fun `saving a name and the other type overrides the suggestion, saving the suggested type clears the override`() = runTest {
        val viewModel = TripDetailViewModel(TripRepository(database, clock), insertFinishedDrive(), flowOf(false), StoredHeightsOnly)
        viewModel.uiState.first { !it.isLoading }

        viewModel.saveTripDetails("  Jeep to the spring ", TripType.WALK)
        val overridden = viewModel.uiState.first { it.trip?.name != null }
        assertEquals("Jeep to the spring", overridden.trip!!.name)
        assertEquals(TripType.WALK, overridden.trip!!.typeOverride)
        assertEquals(TripType.WALK, overridden.type)
        assertEquals(TripType.DRIVE, overridden.suggestedType)

        viewModel.saveTripDetails("Jeep to the spring", TripType.DRIVE)
        val automatic = viewModel.uiState.first { it.trip?.typeOverride == null }
        assertEquals("Jeep to the spring", automatic.trip!!.name)
        assertEquals(TripType.DRIVE, automatic.type)

        viewModel.saveTripDetails("", TripType.DRIVE)
        assertNull(viewModel.uiState.first { it.trip?.name == null }.trip!!.name)
    }

    /** A phone that can't convert heights (Android 13 or older): the stored heights are kept. */
    private object StoredHeightsOnly : SeaLevelHeights {
        override suspend fun toSeaLevel(points: List<TripPoint>): List<TripPoint>? = null
    }

    /** Converts by taking [geoidMeters] off every height, as Android 14's converter does with the real geoid; counts its calls. */
    private class FixedGeoid(private val geoidMeters: Double) : SeaLevelHeights {
        var calls = 0
        override suspend fun toSeaLevel(points: List<TripPoint>): List<TripPoint> {
            calls++
            return points.map { point -> point.copy(altitudeMeters = point.altitudeMeters?.let { it - geoidMeters }) }
        }
    }

    /** A FINISHED walk heading north at 4.5 km/h, 20 minutes, a 20-minute break, 20 more minutes, climbing from 700 m (as stored) to 820 m. */
    private suspend fun insertHillWalk(): Long {
        val start = fixedNow.epochSecond - 3_600
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = start,
                endEpochSecond = fixedNow.epochSecond,
                startZoneId = "Asia/Jerusalem",
                state = TripState.FINISHED.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = fixedNow.epochSecond,
                createdAtEpochSecond = start,
            ),
        )
        var time = start
        var meters = 0.0
        for (i in 0..240) {
            if (i == 121) time += 1_200 // the break: no fixes while standing still
            database.tripPointDao().insert(
                TripPointEntity(
                    tripId = tripId,
                    capturedAtEpochSecond = time,
                    latitude = 31.77 + meters / 111_195.0,
                    longitude = 35.2,
                    accuracyMeters = 5f,
                    altitudeMeters = 700.0 + meters * 0.04,
                    speedMetersPerSecond = 1.25f,
                ),
            )
            time += 10
            meters += 12.5
        }
        return tripId
    }

    @Test
    fun `heights are converted to above sea level for the elevation and the GPX`() = runTest {
        val viewModel = TripDetailViewModel(TripRepository(database, clock), insertHillWalk(), flowOf(false), FixedGeoid(20.0))

        val state = viewModel.uiState.first { !it.isLoading }

        assertTrue(state.heightsAboveSeaLevel)
        assertEquals(680.0, state.points.first().altitudeMeters!!, 1e-9)
        val elevation = state.elevation!!
        assertEquals(800.0, elevation.highestMeters, 3.0)
        assertEquals(680.0, elevation.lowestMeters, 3.0)
        assertEquals(120.0, elevation.climbMeters, 6.0)
        assertEquals(TripElevation.measure(state.points), elevation)
        // GPX writes the same converted heights the screen shows.
        assertTrue(GpxExport.toGpx(state.points).contains("<ele>680.0000000</ele>"))
    }

    @Test
    fun `where the phone can't convert, the stored heights stay and are only relative`() = runTest {
        val viewModel = TripDetailViewModel(TripRepository(database, clock), insertHillWalk(), flowOf(false), StoredHeightsOnly)

        val state = viewModel.uiState.first { !it.isLoading }

        assertFalse(state.heightsAboveSeaLevel)
        assertEquals(700.0, state.points.first().altitudeMeters!!, 1e-9)
        // Climb and descent don't depend on the height reference.
        assertEquals(120.0, state.elevation!!.climbMeters, 6.0)
        assertTrue(GpxExport.toGpx(state.points).contains("<ele>700.0000000</ele>"))
    }

    @Test
    fun `a trip without heights has no elevation`() = runTest {
        val viewModel = TripDetailViewModel(TripRepository(database, clock), insertFinishedDrive(), flowOf(false), FixedGeoid(20.0))

        val state = viewModel.uiState.first { !it.isLoading }

        assertNull(state.elevation)
        assertTrue(state.points.all { it.altitudeMeters == null })
    }

    @Test
    fun `moving time leaves out the break and top speed comes from the displayed route`() = runTest {
        val viewModel = TripDetailViewModel(TripRepository(database, clock), insertHillWalk(), flowOf(false), StoredHeightsOnly)

        val state = viewModel.uiState.first { !it.isLoading }

        val motion = state.motion!!
        assertEquals(TripMotion.measure(state.points, TripType.WALK), motion)
        assertEquals(40.0, motion.movingSeconds / 60.0, 0.5)
        assertEquals(4.5, motion.topSpeedMetersPerSecond!! * 3.6, 0.2)
        // Never below the speed legend's fast end.
        assertTrue(motion.topSpeedMetersPerSecond!! >= state.speedColoring!!.scale!!.fastMetersPerSecond)
    }

    @Test
    fun `moving time follows the trip's type, and renaming doesn't redo the route`() = runTest {
        val geoid = FixedGeoid(20.0)
        val viewModel = TripDetailViewModel(TripRepository(database, clock), insertHillWalk(), flowOf(false), geoid)
        val walk = viewModel.uiState.first { !it.isLoading }
        assertEquals(TripMotion.measure(walk.points, TripType.WALK), walk.motion)

        viewModel.saveTripDetails("Up the hill", TripType.DRIVE)
        val drive = viewModel.uiState.first { it.trip?.name != null }

        assertEquals(TripMotion.measure(drive.points, TripType.DRIVE), drive.motion)
        assertEquals(1, geoid.calls)
    }

    /**
     * Room tells a query that its table changed, not which rows did: while another trip records,
     * every fix it keeps makes this finished trip's points query run again and return the same
     * list. Looking at an old trip during a recording must not redo its whole route (cleanup,
     * smoothing, sea-level heights, colors, heights) every few seconds.
     */
    @Test
    fun `another trip recording doesn't redo this trip's route`() = runTest {
        val geoid = FixedGeoid(20.0)
        val repository = TripRepository(database, clock)
        val viewModel = TripDetailViewModel(repository, insertHillWalk(), flowOf(false), geoid)
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.uiState.first { !it.isLoading }
        val recording = database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond,
                endEpochSecond = null,
                startZoneId = "UTC",
                state = TripState.ACTIVE.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = fixedNow.epochSecond,
            ),
        )

        for (i in 1..3) {
            database.tripPointDao().insert(
                TripPointEntity(
                    tripId = recording,
                    capturedAtEpochSecond = fixedNow.epochSecond + i * 10L,
                    latitude = 32.0 + i * 1e-4,
                    longitude = 35.0,
                    accuracyMeters = 5f,
                    altitudeMeters = 500.0,
                    speedMetersPerSecond = 1.2f,
                ),
            )
            // The recording's own query sees the new fix, so the invalidation has gone out.
            repository.observeTripPoints(recording).first { it.size == i }
        }
        withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { while (geoid.calls == 1) delay(10) } }

        assertEquals("the route was worked out once", 1, geoid.calls)
    }

    @Test
    fun `a route with fewer than two points has no moving time`() = runTest {
        val tripId = database.tripDao().insert(
            TripEntity(
                startEpochSecond = fixedNow.epochSecond - 60,
                endEpochSecond = fixedNow.epochSecond,
                startZoneId = "UTC",
                state = TripState.FINISHED.name,
                distanceMeters = 0.0,
                lastAcceptedPointEpochSecond = null,
                createdAtEpochSecond = fixedNow.epochSecond - 60,
            ),
        )
        val viewModel = TripDetailViewModel(TripRepository(database, clock), tripId, flowOf(false), StoredHeightsOnly)

        val state = viewModel.uiState.first { !it.isLoading }

        assertNull(state.motion)
        assertNull(state.elevation)
    }

    private suspend fun insertSpots(tripId: Long) {
        database.tripSpotDao().insert(
            TripSpotEntity(tripId = tripId, number = 1, capturedAtEpochSecond = fixedNow.epochSecond - 3_000, latitude = 31.775, longitude = 35.2, altitudeMeters = 760.0),
        )
        database.tripSpotDao().insert(
            TripSpotEntity(tripId = tripId, number = 2, capturedAtEpochSecond = fixedNow.epochSecond - 1_000, latitude = 31.78, longitude = 35.2, altitudeMeters = null),
        )
    }

    @Test
    fun `spots come by number with their heights converted like the route's, and can be renamed and deleted`() = runTest {
        val tripId = insertHillWalk()
        insertSpots(tripId)
        val viewModel = TripDetailViewModel(TripRepository(database, clock), tripId, flowOf(false), FixedGeoid(20.0))

        val state = viewModel.uiState.first { it.spots.size == 2 }
        assertTrue(state.spotHeightsAboveSeaLevel)
        assertEquals(listOf(1, 2), state.spots.map { it.number })
        assertEquals(740.0, state.spots[0].altitudeMeters!!, 1e-9)
        assertNull(state.spots[1].altitudeMeters)

        viewModel.renameSpot(state.spots[0].id, "  Viewpoint ")
        assertEquals("Viewpoint", viewModel.uiState.first { it.spots.firstOrNull()?.name != null }.spots[0].name)

        viewModel.deleteSpot(state.spots[0].id)
        assertEquals(listOf(2), viewModel.uiState.first { it.spots.size == 1 }.spots.map { it.number })
    }

    @Test
    fun `where the phone can't convert, spot heights are kept but not called above sea level`() = runTest {
        val tripId = insertHillWalk()
        insertSpots(tripId)
        val viewModel = TripDetailViewModel(TripRepository(database, clock), tripId, flowOf(false), StoredHeightsOnly)

        val state = viewModel.uiState.first { it.spots.size == 2 }
        assertFalse(state.spotHeightsAboveSeaLevel)
        assertEquals(760.0, state.spots[0].altitudeMeters!!, 1e-9)
    }
}
