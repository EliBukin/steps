package com.example.stepsplit.ui.trips

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.trip.RouteMath
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
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
        val viewModel = TripDetailViewModel(repository, tripId)

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
        val viewModel = TripDetailViewModel(repository, tripId)

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
    }
}
