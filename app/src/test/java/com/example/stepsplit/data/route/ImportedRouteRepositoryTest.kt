package com.example.stepsplit.data.route

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.trip.TripRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Importing made-up GPX files into a real (in-memory) database, and what the Routes list does with them. */
@RunWith(RobolectricTestRunner::class)
class ImportedRouteRepositoryTest {

    private lateinit var database: StepSplitDatabase
    private lateinit var repository: ImportedRouteRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-31T08:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StepSplitDatabase::class.java).build()
        repository = ImportedRouteRepository(database, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** A made-up walk: [points] points north from 31.0, 35.0, about 11 m apart, climbing 1 m each, and two waypoints. */
    private fun walk(name: String? = "Made-up walk", points: Int = 101, trackName: String? = null) = buildString {
        append("<gpx>")
        if (name != null) append("<metadata><name>$name</name></metadata>")
        append("""<wpt lat="31.0001" lon="35.0001"><ele>99</ele><name>Gate</name><desc>Closes at dusk</desc></wpt><wpt lat="31.0005" lon="35.0"/>""")
        append("<trk>")
        if (trackName != null) append("<name>$trackName</name>")
        append("<trkseg>")
        for (i in 0 until points) append("""<trkpt lat="${31.0 + i * 0.0001}" lon="35.0"><ele>${100 + i}</ele></trkpt>""")
        append("</trkseg></trk></gpx>")
    }

    private fun file(text: String, name: String? = "walk.gpx") = RouteFile(text.toByteArray(), name)

    @Test
    fun `an import keeps the route with its numbers, shown on the map`() = runTest {
        val outcome = repository.import(file(walk())) as RouteImportOutcome.Imported

        val route = outcome.route
        assertEquals("Made-up walk", route.name)
        assertTrue(route.shown)
        assertEquals(1_112.0, route.distanceMeters, 5.0)
        assertEquals(100.0, route.climbMeters!!, 10.0)
        assertEquals(1, route.lineCount)
        assertEquals(101, route.pointCount)
        assertEquals(2, route.waypointCount)
        assertEquals(31.0, route.bounds.minLatitude, 1e-9)
        assertEquals(31.01, route.bounds.maxLatitude, 1e-9)
        assertFalse(outcome.thinned)
        assertFalse(outcome.damaged)
        assertEquals(listOf(route), repository.observeRoutes().first())

        val stored = database.importedRouteDao().get(route.id)!!
        assertEquals("walk.gpx", stored.fileName)
        assertEquals(clock.instant().epochSecond, stored.importedAtEpochSecond)
    }

    @Test
    fun `the route reads back as imported, heights and waypoint notes included`() = runTest {
        val route = (repository.import(file(walk())) as RouteImportOutcome.Imported).route

        val geometry = repository.geometry(route.id)!!

        val segment = geometry.lines.single().segments.single()
        assertEquals(101, segment.size)
        assertEquals(31.005, segment.latitudes[50], 1e-9)
        assertEquals(150.0, segment.elevations[50], 1e-9)
        assertEquals("Closes at dusk", geometry.waypoints[0].note)
        assertNull(geometry.waypoints[1].name)
    }

    @Test
    fun `segments and lines come back apart, as in the file`() = runTest {
        val text = """<gpx><trk><trkseg><trkpt lat="31" lon="35"/><trkpt lat="31.1" lon="35"/></trkseg>
            <trkseg><trkpt lat="31.2" lon="35"/><trkpt lat="31.3" lon="35"/><trkpt lat="31.4" lon="35"/></trkseg></trk>
            <rte><rtept lat="32" lon="35"/><rtept lat="32.1" lon="35"/></rte></gpx>"""
        val route = (repository.import(file(text)) as RouteImportOutcome.Imported).route

        val lines = repository.geometry(route.id)!!.lines

        assertEquals(listOf(listOf(2, 3), listOf(2)), lines.map { line -> line.segments.map { it.size } })
        assertEquals(2, route.lineCount)
    }

    @Test
    fun `without a name of its own, the route is called after the track, else the file`() = runTest {
        val byTrack = (repository.import(file(walk(name = null, trackName = "Track name"))) as RouteImportOutcome.Imported).route
        val byFile = (repository.import(file(walk(name = null, points = 50), name = "Wadi day.GPX")) as RouteImportOutcome.Imported).route
        val nameless = (repository.import(file(walk(name = null, points = 40), name = null)) as RouteImportOutcome.Imported).route

        assertEquals("Track name", byTrack.name)
        assertEquals("Wadi day", byFile.name)
        assertNull(nameless.name)
    }

    @Test
    fun `the same file again is found, and shown again, not added twice`() = runTest {
        val first = (repository.import(file(walk())) as RouteImportOutcome.Imported).route
        repository.setShown(first.id, false)

        val again = repository.import(file(walk(), name = "copy of walk.gpx"))

        assertEquals(RouteImportOutcome.AlreadyImported(first.copy(shown = true)), again)
        assertEquals(1, repository.observeRoutes().first().size)
        assertTrue(repository.observeRoutes().first().single().shown)
    }

    @Test
    fun `the same file twice at once is imported once and found once, never a crash`() = runBlocking {
        // One worker thread, as a busy Dispatchers.IO can be: each import's database calls let the other one run in between.
        val single = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val repository = ImportedRouteRepository(database, clock, io = single)
            val same = file(walk(points = 20_000))
            val outcomes = listOf(async { repository.import(same) }, async { repository.import(same) }).awaitAll()

            assertEquals(1, outcomes.count { it is RouteImportOutcome.Imported })
            assertEquals(1, outcomes.count { it is RouteImportOutcome.AlreadyImported })
            assertEquals(1, repository.observeRoutes().first().size)
        } finally {
            single.close()
        }
    }

    @Test
    fun `a file of deeply nested tags is a clean failure`() = runTest {
        val nested = "<gpx>" + "<x>".repeat(200_000)

        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.DAMAGED), repository.import(file(nested)))
        assertTrue(repository.observeRoutes().first().isEmpty())
    }

    @Test
    fun `rename, show and hide, and delete`() = runTest {
        val route = (repository.import(file(walk())) as RouteImportOutcome.Imported).route

        repository.rename(route.id, "  Ridge \n walk ")
        assertEquals("Ridge walk", repository.observeRoutes().first().single().name)
        // Blank goes back to the file's name.
        repository.rename(route.id, "   ")
        assertEquals("walk", repository.observeRoutes().first().single().name)

        repository.setShown(route.id, false)
        assertFalse(repository.observeRoutes().first().single().shown)

        repository.delete(route.id)
        assertTrue(repository.observeRoutes().first().isEmpty())
        assertNull(repository.geometry(route.id))
        // Its points and waypoints went with it.
        assertTrue(database.importedRouteDao().points(route.id).isEmpty())
        assertTrue(database.importedRouteDao().waypoints(route.id).isEmpty())
    }

    @Test
    fun `a file with more points than the app keeps is thinned to fit, and says so`() = runTest {
        val small = ImportedRouteRepository(database, clock, maxPoints = 50)
        // A wiggly line, so thinning has something to keep.
        val text = buildString {
            append("<gpx><trk><trkseg>")
            for (i in 0 until 2_000) append("""<trkpt lat="${31.0 + i * 0.0001}" lon="${35.0 + if (i % 40 < 20) 0.0 else 0.002}"/>""")
            append("</trkseg></trk></gpx>")
        }

        val outcome = small.import(file(text)) as RouteImportOutcome.Imported

        assertTrue(outcome.thinned)
        assertTrue(outcome.route.pointCount <= 50)
        assertEquals(outcome.route.pointCount, database.importedRouteDao().points(outcome.route.id).size)
    }

    @Test
    fun `a file cut short keeps what it has, and says so`() = runTest {
        val full = walk()
        val outcome = repository.import(file(full.substring(0, full.length / 2))) as RouteImportOutcome.Imported

        assertTrue(outcome.damaged)
        assertTrue(outcome.route.pointCount in 2 until 101)
    }

    @Test
    fun `files that can't be imported say why and leave nothing behind`() = runTest {
        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.NOT_GPX), repository.import(file("<html><body>Not a route</body></html>")))
        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.NOT_GPX), repository.import(file("<!DOCTYPE gpx [<!ENTITY a \"a\">]><gpx/>")))
        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.KML), repository.import(file("<kml><Document/></kml>")))
        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.NOTHING_TO_SHOW), repository.import(file("<gpx><trk><trkseg><trkpt lat=\"31\" lon=\"35\"/></trkseg></trk></gpx>")))
        assertEquals(RouteImportOutcome.Failed(RouteImportProblem.DAMAGED), repository.import(file("<gpx><trk><trkseg><trkpt lat=\"31\" lon=\"35\"")))
        assertEquals(
            RouteImportOutcome.Failed(RouteImportProblem.TOO_BIG),
            repository.import(RouteFile(ByteArray(ImportedRouteRepository.MAX_FILE_BYTES + 1), "huge.gpx")),
        )
        assertTrue(repository.observeRoutes().first().isEmpty())
    }

    @Test
    fun `a file of waypoints only is imported, with no line`() = runTest {
        val outcome = repository.import(file("""<gpx><wpt lat="31" lon="35"><name>Spring</name></wpt><wpt lat="31.01" lon="35.01"/></gpx>""")) as RouteImportOutcome.Imported

        assertEquals(0, outcome.route.lineCount)
        assertEquals(0.0, outcome.route.distanceMeters, 0.0)
        assertEquals(2, outcome.route.waypointCount)
        assertTrue(repository.geometry(outcome.route.id)!!.lines.isEmpty())
    }

    @Test
    fun `imported routes are not trips`() = runTest {
        repository.import(file(walk()))

        val trips = TripRepository(database, clock)
        assertTrue(trips.observeTrips().first().isEmpty())
        for (table in listOf("trips", "trip_points", "trip_spots", "deleted_trip_totals", "step_buckets")) {
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(table, 0, cursor.getInt(0))
            }
        }
    }
}
