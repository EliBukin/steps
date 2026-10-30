package com.example.stepsplit.data.mappack

import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The list of offline map packs on the download server (`maps.json`, see
 * scripts/offline-maps/README.md): which regions there are, their current version, and the files
 * to download for each. Made by scripts/offline-maps/make_manifest.py; read by
 * [MapPackRepository] when the Offline maps settings open, never in the background.
 */
data class MapPackManifest(val schema: Int, val regions: List<MapPackRegion>) {
    companion object {
        /**
         * The pack layout this app can draw: the layer names in the vector file and the DEM's
         * encoding, which the app's pack style ([com.example.stepsplit.ui.map.MapPackStyle]) reads.
         * A manifest with a higher schema needs a newer app.
         */
        const val SCHEMA = 1

        /**
         * Reads [json], resolving each file's name against [manifestUrl] (the files sit next to the
         * manifest). Null when it isn't a manifest this app understands.
         */
        fun parse(json: String, manifestUrl: String): MapPackManifest? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            MapPackManifest(
                schema = root.getValue("schema").jsonPrimitive.int,
                regions = root.getValue("regions").jsonArray.map { region(it.jsonObject, manifestUrl) },
            )
        }.getOrNull()

        private fun region(json: JsonObject, manifestUrl: String) = MapPackRegion(
            id = json.getValue("id").jsonPrimitive.content,
            version = json.getValue("version").jsonPrimitive.content,
            minAppVersionCode = json["minAppVersionCode"]?.jsonPrimitive?.intOrNull ?: 0,
            area = MapPackArea.fromJson(json.getValue("area").jsonArray),
            files = json.getValue("files").jsonArray.map { file ->
                val f = file.jsonObject
                val name = f.getValue("name").jsonPrimitive.content
                require(MapPackFile.isSafeName(name)) { "bad file name $name" }
                MapPackFile(
                    role = f.getValue("role").jsonPrimitive.content,
                    name = name,
                    url = URL(URL(manifestUrl), f["url"]?.jsonPrimitive?.contentOrNull ?: name).toString(),
                    bytes = f.getValue("bytes").jsonPrimitive.long,
                    sha256 = f.getValue("sha256").jsonPrimitive.content.lowercase(),
                )
            },
        )
    }
}

/** One downloadable region ("israel") in one version (its build date, "2026-10-29"). */
data class MapPackRegion(
    val id: String,
    val version: String,
    val minAppVersionCode: Int,
    val area: MapPackArea,
    val files: List<MapPackFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    /** Has the two files the app draws from. */
    val isComplete: Boolean get() = files.any { it.role == MapPackFile.ROLE_VECTOR } && files.any { it.role == MapPackFile.ROLE_DEM }
}

data class MapPackFile(val role: String, val name: String, val url: String, val bytes: Long, val sha256: String) {
    companion object {
        /** The map itself: roads, tracks, trails, wadis, contours, labels (OpenMapTiles layers plus the outdoor ones). */
        const val ROLE_VECTOR = "vector"

        /** Heights for the hill shading (Terrarium-encoded WebP tiles). */
        const val ROLE_DEM = "dem"

        /** A plain file name: no folders, so a manifest can never point a download outside the pack's folder. */
        fun isSafeName(name: String) = name.matches(Regex("[A-Za-z0-9._-]+")) && !name.startsWith(".")
    }
}

/**
 * Where a pack has map data: one or more rings of longitude/latitude points (the extract's
 * outline, simplified and a little widened). The map uses the pack while its center is inside.
 */
class MapPackArea(private val rings: List<DoubleArray>) {
    /** Even-odd rule over all rings, so separate parts and holes both work. */
    fun contains(longitude: Double, latitude: Double): Boolean {
        var inside = false
        for (ring in rings) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i]
                val yi = ring[2 * i + 1]
                val xj = ring[2 * j]
                val yj = ring[2 * j + 1]
                if ((yi > latitude) != (yj > latitude) && longitude < (xj - xi) * (latitude - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    fun toJson(): JsonArray = JsonArray(
        rings.map { ring -> JsonArray((0 until ring.size / 2).map { JsonArray(listOf(jsonNumber(ring[2 * it]), jsonNumber(ring[2 * it + 1]))) }) },
    )

    companion object {
        fun fromJson(json: JsonArray) = MapPackArea(
            json.map { ring ->
                val points = ring.jsonArray
                require(points.size >= 3) { "ring with ${points.size} points" }
                DoubleArray(points.size * 2) { points[it / 2].jsonArray[it % 2].jsonPrimitive.double }
            },
        )

        private fun jsonNumber(value: Double) = kotlinx.serialization.json.JsonPrimitive(value)
    }
}
