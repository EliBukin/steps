package com.example.stepsplit.data.local.route

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedRouteDao {

    /** Newest import first. */
    @Query("SELECT * FROM imported_routes ORDER BY importedAtEpochSecond DESC, id DESC")
    fun observeAll(): Flow<List<ImportedRouteEntity>>

    @Query("SELECT * FROM imported_routes WHERE id = :id")
    suspend fun get(id: Long): ImportedRouteEntity?

    @Query("SELECT * FROM imported_routes WHERE contentSha256 = :sha256 LIMIT 1")
    suspend fun findByContent(sha256: String): ImportedRouteEntity?

    @Insert
    suspend fun insert(route: ImportedRouteEntity): Long

    @Insert
    suspend fun insertPoints(points: List<ImportedRoutePointEntity>)

    @Insert
    suspend fun insertWaypoints(waypoints: List<ImportedRouteWaypointEntity>)

    @Query("SELECT * FROM imported_route_points WHERE routeId = :routeId ORDER BY seq ASC")
    suspend fun points(routeId: Long): List<ImportedRoutePointEntity>

    @Query("SELECT * FROM imported_route_waypoints WHERE routeId = :routeId ORDER BY seq ASC")
    suspend fun waypoints(routeId: Long): List<ImportedRouteWaypointEntity>

    /** Returns how many rows changed (0 for an unknown route). */
    @Query("UPDATE imported_routes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?): Int

    @Query("UPDATE imported_routes SET shown = :shown WHERE id = :id")
    suspend fun setShown(id: Long, shown: Boolean): Int

    /** Its points and waypoints go with it (`ON DELETE CASCADE`). */
    @Query("DELETE FROM imported_routes WHERE id = :id")
    suspend fun delete(id: Long)
}
