package com.example.stepsplit.ui.map

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointEntity
import com.example.stepsplit.data.local.trip.TripSpotEntity
import com.example.stepsplit.data.route.ImportedRouteRepository
import com.example.stepsplit.data.route.RouteFile
import com.example.stepsplit.data.route.RouteFileRead
import com.example.stepsplit.data.route.RouteFileReader
import com.example.stepsplit.data.route.RouteImportOutcome
import com.example.stepsplit.data.route.RouteImportProblem
import com.example.stepsplit.data.trip.MapFix
import com.example.stepsplit.data.trip.MapLocationClient
import com.example.stepsplit.data.trip.TripRepository
import com.example.stepsplit.domain.model.TripPoint
import com.example.stepsplit.domain.model.TripState
import com.example.stepsplit.domain.route.RouteFollower
import com.example.stepsplit.domain.trip.RouteSanitizer
import com.example.stepsplit.domain.trip.RouteSmoother
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Open map's promises: it shows the trip being recorded exactly as the rest of the app does
 * (the same cleaned-up, smoothed route), and it asks for location only while the screen collects
 * [OpenMapViewModel.location] *and* has said location is available - never otherwise. Imported GPX
 * routes: imported, shown, hidden and followed (all made-up files and places).
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
        val viewModel = newViewModel(FakeMapLocationClient())

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
        val viewModel = newViewModel(FakeMapLocationClient())

        val recording = viewModel.uiState.first { it.recording?.spots?.isNotEmpty() == true }.recording!!

        val expected = RouteSmoother.smooth(RouteSanitizer.sanitize(pointsFor(activeId, 6)).points)
        assertEquals(expected.points, recording.route)
        assertEquals(expected.distanceMeters, recording.distanceMeters, 1e-9)
        assertEquals(listOf(1), recording.spots.map { it.number })
    }

    @Test
    fun `location is asked for only while the screen collects it and location is available`() = runTest {
        val client = FakeMapLocationClient(last = fix(1))
        val viewModel = newViewModel(client)

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
        val viewModel = newViewModel(client)
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
        val viewModel = newViewModel(client)
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

    // ---- Imported routes ----

    /** The files the fake reader "opens", by address; anything else can't be read. */
    private val routeFiles = mutableMapOf<Uri, RouteFileRead>()

    /** A file at one of these addresses takes until its gate opens to read (a big file, a slow app). */
    private val slowFiles = mutableMapOf<Uri, CompletableDeferred<Unit>>()

    private fun newViewModel(client: MapLocationClient) = OpenMapViewModel(
        TripRepository(database, clock),
        client,
        ImportedRouteRepository(database, clock),
        RouteFileReader { uri ->
            slowFiles[uri]?.await()
            routeFiles[uri] ?: RouteFileRead.CantRead
        },
    )

    /** A made-up straight walk north from 31.0, 35.0, about 1.1 km, with two waypoints. */
    private val straightRoute = buildString {
        append("<gpx><metadata><name>Made-up straight walk</name></metadata>")
        append("""<wpt lat="31.002" lon="35.0005"><name>Bench</name></wpt><wpt lat="31.008" lon="35.0"><name>Tree</name><desc>Shade</desc></wpt>""")
        append("<trk><trkseg>")
        for (i in 0..10) append("""<trkpt lat="${31.0 + i * 0.001}" lon="35.0"><ele>${100 + i}</ele></trkpt>""")
        append("</trkseg></trk></gpx>")
    }

    private fun offer(name: String, text: String): Uri =
        Uri.parse("content://test/$name").also { routeFiles[it] = RouteFileRead.Read(RouteFile(text.toByteArray(), name)) }

    @Test
    fun `an imported file goes on the map and the map is asked to show the whole of it`() = runTest {
        val viewModel = newViewModel(FakeMapLocationClient())
        val uri = offer("walk.gpx", straightRoute)

        viewModel.importRoute(uri)
        val finished = viewModel.importStatus.first { it is RouteImportStatus.Finished } as RouteImportStatus.Finished

        val outcome = finished.outcome as RouteImportOutcome.Imported
        assertEquals("Made-up straight walk", outcome.route.name)
        val state = viewModel.uiState.first { it.routesOnMap.isNotEmpty() }
        assertEquals(listOf(outcome.route.id), state.importedRoutes.map { it.id })
        assertEquals(11, state.routesOnMap.single().lines.single().pointCount)
        assertEquals(listOf("Bench", "Tree"), state.routesOnMap.single().waypoints.map { it.name })
        assertEquals(outcome.route.bounds, viewModel.zoomRequest.value!!.bounds)

        // Once the map has shown it, it isn't asked again.
        viewModel.onZoomRequestHandled(viewModel.zoomRequest.value!!.id)
        assertNull(viewModel.zoomRequest.value)
        viewModel.dismissImportStatus()
        assertNull(viewModel.importStatus.value)
    }

    @Test
    fun `the same file again is found instead of added twice`() = runTest {
        val viewModel = newViewModel(FakeMapLocationClient())
        val uri = offer("walk.gpx", straightRoute)
        viewModel.importRoute(uri)
        viewModel.importStatus.first { it is RouteImportStatus.Finished }

        viewModel.importRoute(offer("walk-copy.gpx", straightRoute))
        val again = viewModel.importStatus.first { (it as? RouteImportStatus.Finished)?.outcome is RouteImportOutcome.AlreadyImported }

        assertTrue(again is RouteImportStatus.Finished)
        assertEquals(1, viewModel.uiState.first { it.importedRoutes.isNotEmpty() }.importedRoutes.size)
    }

    @Test
    fun `a file that can't be read, is too big or isn't GPX says so and adds nothing`() = runTest {
        val viewModel = newViewModel(FakeMapLocationClient())
        val tooBig = Uri.parse("content://test/big.gpx").also { routeFiles[it] = RouteFileRead.TooBig }

        suspend fun problemFor(uri: Uri): RouteImportProblem {
            viewModel.dismissImportStatus()
            viewModel.importRoute(uri)
            val finished = viewModel.importStatus.first { it is RouteImportStatus.Finished } as RouteImportStatus.Finished
            return (finished.outcome as RouteImportOutcome.Failed).problem
        }

        assertEquals(RouteImportProblem.CANT_READ, problemFor(Uri.parse("content://test/gone.gpx")))
        assertEquals(RouteImportProblem.TOO_BIG, problemFor(tooBig))
        assertEquals(RouteImportProblem.NOT_GPX, problemFor(offer("page.gpx", "<html><body>no</body></html>")))
        assertTrue(viewModel.uiState.first { !it.isLoading }.importedRoutes.isEmpty())
        assertNull(viewModel.zoomRequest.value)
    }

    @Test
    fun `an import that throws is a failed import, not a crash`() = runTest {
        val viewModel = OpenMapViewModel(
            TripRepository(database, clock),
            FakeMapLocationClient(),
            ImportedRouteRepository(database, clock),
            RouteFileReader { throw IllegalStateException("an odd app's file provider") },
        )

        viewModel.importRoute(Uri.parse("content://odd/walk.gpx"))
        val finished = viewModel.importStatus.first { it is RouteImportStatus.Finished } as RouteImportStatus.Finished

        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.CANT_READ), finished.outcome)
        assertNull(viewModel.zoomRequest.value)
    }

    @Test
    fun `with two files at once, the message and the map follow the one asked for last`() = runTest {
        val viewModel = newViewModel(FakeMapLocationClient())
        val first = offer("first.gpx", straightRoute)
        val firstGate = CompletableDeferred<Unit>().also { slowFiles[first] = it }
        val second = offer("second.gpx", straightRoute.replace("Made-up straight walk", "Made-up second walk").replace("lon=\"35.0\"", "lon=\"35.01\""))

        viewModel.importRoute(first)
        viewModel.importRoute(second)
        val finished = viewModel.importStatus.first { it is RouteImportStatus.Finished } as RouteImportStatus.Finished
        val secondRoute = (finished.outcome as RouteImportOutcome.Imported).route
        assertEquals("Made-up second walk", secondRoute.name)
        assertEquals(secondRoute.bounds, viewModel.zoomRequest.value!!.bounds)

        // The first file ends later: it goes into the list, but the message and the map stay on the second.
        firstGate.complete(Unit)
        viewModel.uiState.first { it.importedRoutes.size == 2 }
        advanceUntilIdle()
        assertEquals(finished, viewModel.importStatus.value)
        assertEquals(secondRoute.bounds, viewModel.zoomRequest.value!!.bounds)
    }

    @Test
    fun `hiding a route takes it off the map and keeps it in the list, and deleting removes it`() = runTest {
        val viewModel = newViewModel(FakeMapLocationClient())
        viewModel.importRoute(offer("walk.gpx", straightRoute))
        val route = viewModel.uiState.first { it.routesOnMap.isNotEmpty() }.importedRoutes.single()

        viewModel.setRouteShown(route.id, false)
        val hidden = viewModel.uiState.first { it.routesOnMap.isEmpty() }
        assertEquals(false, hidden.importedRoutes.single().shown)

        // Zoom to route shows it again.
        viewModel.zoomToRoute(hidden.importedRoutes.single())
        assertEquals(route.id, viewModel.uiState.first { it.routesOnMap.isNotEmpty() }.routesOnMap.single().id)

        viewModel.renameRoute(route.id, "  Evening   walk ")
        assertEquals("Evening walk", viewModel.uiState.first { it.importedRoutes.single().name != route.name }.importedRoutes.single().name)

        viewModel.deleteRoute(route.id)
        viewModel.uiState.first { it.importedRoutes.isEmpty() && it.routesOnMap.isEmpty() }
    }

    @Test
    fun `the route strip says how far along the shown route the phone is, while the screen collects it`() = runTest {
        val client = FakeMapLocationClient()
        val viewModel = newViewModel(client)
        viewModel.importRoute(offer("walk.gpx", straightRoute))
        viewModel.uiState.first { it.routesOnMap.isNotEmpty() }
        assertNull(viewModel.routeStrip.value)

        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.routeStrip.collect {} }
        viewModel.setLocationAvailable(true)
        advanceUntilIdle()
        // About 10 m east of the middle of the walk.
        client.send(MapFix(latitude = 31.005, longitude = 35.0001, accuracyMeters = 5f, bearingDegrees = null, elapsedRealtimeNanos = 1L))
        val strip = viewModel.routeStrip.first { it != null }!!

        assertEquals("Made-up straight walk", strip.routeName)
        val progress = strip.progress as RouteFollower.Progress.OnRoute
        assertEquals(556.0, progress.doneMeters, 5.0)
        assertEquals(556.0, progress.leftMeters, 5.0)
        assertEquals(9.5, progress.offMeters, 1.0)

        // The screen goes away: no fixes are asked for, nothing is worked out.
        collecting.cancel()
        advanceUntilIdle()
        assertEquals(0, client.activeRequests.get())
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
