package com.example.stepsplit.data.route

import androidx.room.withTransaction
import com.example.stepsplit.data.local.StepSplitDatabase
import com.example.stepsplit.data.local.route.ImportedRouteEntity
import com.example.stepsplit.data.local.route.ImportedRoutePointEntity
import com.example.stepsplit.data.local.route.ImportedRouteWaypointEntity
import com.example.stepsplit.domain.route.RouteBounds
import com.example.stepsplit.domain.route.RouteGeometry
import com.example.stepsplit.domain.route.RouteLine
import com.example.stepsplit.domain.route.RouteMeasure
import com.example.stepsplit.domain.route.RouteSegment
import com.example.stepsplit.domain.route.RouteSimplifier
import com.example.stepsplit.domain.route.RouteText
import com.example.stepsplit.domain.route.RouteWaypoint
import java.security.MessageDigest
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A GPX file's bytes and the name it had where it came from (null when that app didn't say). */
class RouteFile(val bytes: ByteArray, val displayName: String?)

/** An imported route as the Routes list shows it. */
data class ImportedRoute(
    val id: Long,
    /** Its own name (the file's, or the owner's), else the file name without `.gpx`; null for neither. */
    val name: String?,
    val shown: Boolean,
    val distanceMeters: Double,
    val climbMeters: Double?,
    val lineCount: Int,
    val pointCount: Int,
    val waypointCount: Int,
    val bounds: RouteBounds,
)

/** How an import went. */
sealed interface RouteImportOutcome {
    /**
     * Imported, and shown on the map. [thinned]: the file had more than [ImportedRouteRepository.MAX_POINTS]
     * points, and kept that many. [damaged]: the file broke off part way, and kept what came before.
     */
    data class Imported(val route: ImportedRoute, val thinned: Boolean, val damaged: Boolean) : RouteImportOutcome

    /** The same file was imported before: that route, now shown. */
    data class AlreadyImported(val route: ImportedRoute) : RouteImportOutcome

    data class Failed(val problem: RouteImportProblem) : RouteImportOutcome
}

enum class RouteImportProblem {
    /** The file couldn't be opened or read (the app that shared it took it back, say). */
    CANT_READ,

    /** Over [ImportedRouteRepository.MAX_FILE_BYTES]. */
    TOO_BIG,

    /** Not a GPX file, or one with a DOCTYPE (see [GpxParser]). */
    NOT_GPX,

    /** A KML file: not read yet. */
    KML,

    /** A GPX file with no line (two points or more) and no waypoint. */
    NOTHING_TO_SHOW,

    /** A GPX file broken before anything in it could be read. */
    DAMAGED,
}

/**
 * Routes imported from GPX files: planned hikes and drives to follow on the Map tab. They're kept
 * in their own tables ([ImportedRouteEntity]) - never trips, so the Trips list, Stats and steps
 * never see them. Everything stays on the phone; reading a file uses no network.
 *
 * - **Import** ([import]): the file is read by [GpxParser], thinned to [MAX_POINTS] if it has
 *   more, measured ([RouteMeasure]) and saved in one transaction, shown on the map. A file already
 *   imported (the same bytes) isn't added again.
 * - **The list**: rename, show or hide, delete (with its points and waypoints).
 * - **The map** reads a shown route's lines and waypoints with [geometry].
 */
class ImportedRouteRepository(
    private val database: StepSplitDatabase,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** [MAX_POINTS]; smaller only in tests, so thinning can be tried on a small file. */
    private val maxPoints: Int = MAX_POINTS,
) {
    private val dao = database.importedRouteDao()

    fun observeRoutes(): Flow<List<ImportedRoute>> = dao.observeAll().map { routes -> routes.map { it.toRoute() } }

    suspend fun import(file: RouteFile): RouteImportOutcome = withContext(io) {
        if (file.bytes.size > MAX_FILE_BYTES) return@withContext RouteImportOutcome.Failed(RouteImportProblem.TOO_BIG)
        val sha256 = sha256(file.bytes)
        dao.findByContent(sha256)?.let { existing ->
            dao.setShown(existing.id, true)
            return@withContext RouteImportOutcome.AlreadyImported(existing.copy(shown = true).toRoute())
        }
        val parsed = when (val result = GpxParser.parse(file.bytes)) {
            is GpxParser.Result.Parsed -> result
            GpxParser.Result.Kml -> return@withContext RouteImportOutcome.Failed(RouteImportProblem.KML)
            GpxParser.Result.NotGpx, GpxParser.Result.Refused -> return@withContext RouteImportOutcome.Failed(RouteImportProblem.NOT_GPX)
        }
        if (parsed.lines.isEmpty() && parsed.waypoints.isEmpty()) {
            return@withContext RouteImportOutcome.Failed(if (parsed.damaged) RouteImportProblem.DAMAGED else RouteImportProblem.NOTHING_TO_SHOW)
        }
        val lines = RouteSimplifier.fitToLimit(parsed.lines, maxPoints)
        val thinned = lines.sumOf { it.pointCount } < parsed.lines.sumOf { it.pointCount }
        val measure = RouteMeasure.measure(lines, parsed.waypoints)
        val bounds = checkNotNull(measure.bounds)
        val entity = ImportedRouteEntity(
            name = parsed.fileTitle ?: parsed.firstLineName,
            fileName = RouteText.clean(file.displayName),
            importedAtEpochSecond = clock.instant().epochSecond,
            contentSha256 = sha256,
            shown = true,
            distanceMeters = measure.distanceMeters,
            climbMeters = measure.climbMeters,
            descentMeters = measure.descentMeters,
            lineCount = lines.size,
            pointCount = measure.pointCount,
            waypointCount = parsed.waypoints.size,
            minLatitude = bounds.minLatitude,
            minLongitude = bounds.minLongitude,
            maxLatitude = bounds.maxLatitude,
            maxLongitude = bounds.maxLongitude,
        )
        val id = database.withTransaction {
            val id = dao.insert(entity)
            dao.insertPoints(pointEntities(id, lines))
            dao.insertWaypoints(
                parsed.waypoints.mapIndexed { seq, waypoint ->
                    ImportedRouteWaypointEntity(id, seq, waypoint.latitude, waypoint.longitude, waypoint.elevationMeters, waypoint.name, waypoint.note)
                },
            )
            id
        }
        RouteImportOutcome.Imported(entity.copy(id = id).toRoute(), thinned = thinned, damaged = parsed.damaged)
    }

    /** [name] as typed in the Routes list; blank clears it, so the file name shows again. */
    suspend fun rename(id: Long, name: String?) {
        withContext(io) { dao.rename(id, RouteText.clean(name)) }
    }

    suspend fun setShown(id: Long, shown: Boolean) {
        withContext(io) { dao.setShown(id, shown) }
    }

    suspend fun delete(id: Long) {
        withContext(io) { dao.delete(id) }
    }

    /** [id]'s lines and waypoints as imported, or null once it's gone. */
    suspend fun geometry(id: Long): RouteGeometry? = withContext(io) {
        dao.get(id) ?: return@withContext null
        val points = dao.points(id)
        val waypoints = dao.waypoints(id).map { RouteWaypoint(it.latitude, it.longitude, it.elevationMeters, it.name, it.note) }
        RouteGeometry(linesFrom(points), waypoints)
    }

    companion object {
        /** The biggest file imported. A day's hike with a point every second is well under 5 MB. */
        const val MAX_FILE_BYTES = 25 * 1024 * 1024

        /** The most points one route keeps (see [RouteSimplifier.fitToLimit]). */
        const val MAX_POINTS = 100_000

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun ImportedRouteEntity.toRoute() = ImportedRoute(
            id = id,
            name = name ?: RouteText.nameFromFileName(fileName),
            shown = shown,
            distanceMeters = distanceMeters,
            climbMeters = climbMeters,
            lineCount = lineCount,
            pointCount = pointCount,
            waypointCount = waypointCount,
            bounds = RouteBounds(minLatitude, minLongitude, maxLatitude, maxLongitude),
        )

        private fun pointEntities(routeId: Long, lines: List<RouteLine>): List<ImportedRoutePointEntity> {
            val points = ArrayList<ImportedRoutePointEntity>(lines.sumOf { it.pointCount })
            var seq = 0
            for ((lineIndex, line) in lines.withIndex()) {
                for ((segmentIndex, segment) in line.segments.withIndex()) {
                    for (i in 0 until segment.size) {
                        val elevation = segment.elevations[i].takeIf { it.isFinite() }
                        points += ImportedRoutePointEntity(routeId, seq++, lineIndex, segmentIndex, segment.latitudes[i], segment.longitudes[i], elevation)
                    }
                }
            }
            return points
        }

        /** Back from the stored points (in [ImportedRoutePointEntity.seq] order) to lines and segments. */
        private fun linesFrom(points: List<ImportedRoutePointEntity>): List<RouteLine> {
            val lines = mutableListOf<RouteLine>()
            var i = 0
            while (i < points.size) {
                val line = points[i].line
                val segments = mutableListOf<RouteSegment>()
                while (i < points.size && points[i].line == line) {
                    val segment = points[i].segment
                    val start = i
                    while (i < points.size && points[i].line == line && points[i].segment == segment) i++
                    val count = i - start
                    if (count >= 2) {
                        segments += RouteSegment(
                            DoubleArray(count) { points[start + it].latitude },
                            DoubleArray(count) { points[start + it].longitude },
                            DoubleArray(count) { points[start + it].elevationMeters ?: Double.NaN },
                        )
                    }
                }
                if (segments.isNotEmpty()) lines += RouteLine(segments)
            }
            return lines
        }
    }
}
