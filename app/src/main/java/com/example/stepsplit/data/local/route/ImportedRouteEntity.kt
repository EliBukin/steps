package com.example.stepsplit.data.local.route

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A route imported from a GPX file (schema v11) - a planned hike or drive to follow on the Map tab.
 * Not a trip: nothing in trips, stats or steps reads these tables.
 * [com.example.stepsplit.data.route.ImportedRouteRepository] is the only writer.
 *
 * - [name] is the file's own name for the route, or the owner's after a rename; null shows
 *   [fileName] without its `.gpx`, or a plain "Imported route".
 * - [contentSha256] is the file's SHA-256, so the same file imported again is found instead of
 *   being added twice. Unique.
 * - [distanceMeters], [climbMeters], [descentMeters], the counts and the bounds are worked out once
 *   at import (see [com.example.stepsplit.domain.route.RouteMeasure]); the climb is null when the
 *   file has too few heights.
 * - [shown]: whether the Map tab draws it.
 */
@Entity(
    tableName = "imported_routes",
    indices = [Index(value = ["contentSha256"], unique = true)],
)
data class ImportedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    val fileName: String?,
    val importedAtEpochSecond: Long,
    val contentSha256: String,
    val shown: Boolean,
    val distanceMeters: Double,
    val climbMeters: Double?,
    val descentMeters: Double?,
    val lineCount: Int,
    val pointCount: Int,
    val waypointCount: Int,
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
)

/**
 * One point of an imported route: [seq] orders all of a route's points; [line] (a track or route of
 * the file) and [segment] (a piece of that line) say which line it draws in. [elevationMeters] is
 * the file's height, above sea level, or null. Deleted with its route.
 */
@Entity(
    tableName = "imported_route_points",
    primaryKeys = ["routeId", "seq"],
    foreignKeys = [
        ForeignKey(entity = ImportedRouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImportedRoutePointEntity(
    val routeId: Long,
    val seq: Int,
    val line: Int,
    val segment: Int,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
)

/** One waypoint of an imported route, in the file's order ([seq]), with its name and note when the file has them. Deleted with its route. */
@Entity(
    tableName = "imported_route_waypoints",
    primaryKeys = ["routeId", "seq"],
    foreignKeys = [
        ForeignKey(entity = ImportedRouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ImportedRouteWaypointEntity(
    val routeId: Long,
    val seq: Int,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val name: String?,
    val note: String?,
)
