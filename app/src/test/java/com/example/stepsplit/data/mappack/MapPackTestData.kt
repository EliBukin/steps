package com.example.stepsplit.data.mappack

import java.io.File
import java.security.MessageDigest

/** A made-up two-file pack for the map pack tests: a square "region" around central Israel. */
internal object MapPackTestData {
    const val MANIFEST_URL = "https://example.org/releases/download/maps/maps.json"
    val vectorBytes = "vector tiles".toByteArray()
    val demBytes = "terrain tiles".toByteArray()

    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun manifestJson(
        version: String = "2026-10-29",
        schema: Int = 1,
        minAppVersionCode: Int = 1,
        vectorSha: String = sha256(vectorBytes),
    ) = """
        {"schema": $schema, "regions": [{
          "id": "israel", "version": "$version", "minAppVersionCode": $minAppVersionCode,
          "area": [[[34.5, 31.0], [35.5, 31.0], [35.5, 32.0], [34.5, 32.0], [34.5, 31.0]]],
          "files": [
            {"role": "vector", "name": "israel-$version-vector.pmtiles", "bytes": ${vectorBytes.size}, "sha256": "$vectorSha"},
            {"role": "dem", "name": "israel-$version-dem.pmtiles", "bytes": ${demBytes.size}, "sha256": "${sha256(demBytes)}"}
          ]}]}
    """.trimIndent()

    /** What each file's address downloads to. */
    fun contentFor(url: String): ByteArray = if (url.endsWith("vector.pmtiles")) vectorBytes else demBytes
}

/**
 * Android's download manager stand-in: each [status] call moves every download one step along
 * [script] (default: queued, running, done), writing the file's content when it's done.
 */
internal class FakeMapPackDownloader(
    private val script: List<MapPackDownloadStatus.State> = listOf(
        MapPackDownloadStatus.State.QUEUED,
        MapPackDownloadStatus.State.RUNNING,
        MapPackDownloadStatus.State.DONE,
    ),
    private val content: (String) -> ByteArray = MapPackTestData::contentFor,
) : MapPackDownloader {
    private class Download(val url: String, val destination: File, var step: Int = -1)

    private val downloads = mutableMapOf<Long, Download>()
    private var nextId = 100L
    val enqueued = mutableListOf<String>()
    val cancelled = mutableListOf<Long>()
    var wifiOnlyRequested: Boolean? = null

    override fun enqueue(url: String, destination: File, title: String, wifiOnly: Boolean): Long {
        enqueued += url
        wifiOnlyRequested = wifiOnly
        return nextId++.also { downloads[it] = Download(url, destination) }
    }

    override fun status(ids: List<Long>): List<MapPackDownloadStatus> = ids.map { id ->
        val download = downloads[id] ?: return@map MapPackDownloadStatus(id, MapPackDownloadStatus.State.GONE, 0, -1)
        download.step = (download.step + 1).coerceAtMost(script.lastIndex)
        val state = script[download.step]
        val bytes = content(download.url)
        if (state == MapPackDownloadStatus.State.DONE) download.destination.writeBytes(bytes)
        val soFar = when (state) {
            MapPackDownloadStatus.State.DONE -> bytes.size.toLong()
            MapPackDownloadStatus.State.RUNNING -> bytes.size / 2L
            else -> 0L
        }
        MapPackDownloadStatus(id, state, soFar, bytes.size.toLong())
    }

    override fun cancel(ids: List<Long>) {
        cancelled += ids
        ids.forEach { downloads.remove(it)?.destination?.delete() }
    }

    override fun unfinishedInto(dir: File): List<Long> = downloads.filterValues { download ->
        val state = if (download.step < 0) MapPackDownloadStatus.State.QUEUED else script[download.step]
        state != MapPackDownloadStatus.State.DONE && state != MapPackDownloadStatus.State.FAILED &&
            download.destination.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    }.keys.toList()
}

internal class InMemoryMapPackPreferences(override var wifiOnly: Boolean = true, override var offerAnswered: Boolean = false) : MapPackPreferences
