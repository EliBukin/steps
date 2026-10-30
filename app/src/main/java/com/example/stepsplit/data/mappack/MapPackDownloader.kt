package com.example.stepsplit.data.mappack

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File

/** Downloads a pack's files; [SystemMapPackDownloader] in the app, a fake in tests. */
interface MapPackDownloader {
    /** Starts downloading [url] into [destination] and returns the download's id. */
    fun enqueue(url: String, destination: File, title: String, wifiOnly: Boolean): Long

    fun status(ids: List<Long>): List<MapPackDownloadStatus>

    /** Stops the downloads and deletes what they had written. */
    fun cancel(ids: List<Long>)
}

data class MapPackDownloadStatus(val id: Long, val state: State, val downloadedBytes: Long, val totalBytes: Long) {
    enum class State { QUEUED, RUNNING, WAITING_FOR_NETWORK, WAITING_FOR_WIFI, DONE, FAILED, GONE }
}

/**
 * Android's own download manager: it resumes after a dropped connection or a restart, waits for
 * Wi-Fi when asked, and shows the progress notification - with no foreground service or extra
 * permission in this app. It downloads into the app's own folder on the phone's storage
 * ([MapPackStore.root]), the only place it may write for the app without a permission.
 */
class SystemMapPackDownloader(context: Context) : MapPackDownloader {
    private val downloads = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(url: String, destination: File, title: String, wifiOnly: Boolean): Long {
        destination.parentFile?.mkdirs()
        destination.delete()
        val request = DownloadManager.Request(url.toUri())
            .setTitle(title)
            .setDestinationUri(Uri.fromFile(destination))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            // "Wi-Fi only" = never over a metered connection (mobile data, a phone's hotspot).
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        return downloads.enqueue(request)
    }

    override fun status(ids: List<Long>): List<MapPackDownloadStatus> {
        if (ids.isEmpty()) return emptyList()
        val found = mutableMapOf<Long, MapPackDownloadStatus>()
        downloads.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reasonColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val soFarColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val state = when (cursor.getInt(statusColumn)) {
                    DownloadManager.STATUS_RUNNING -> MapPackDownloadStatus.State.RUNNING
                    DownloadManager.STATUS_SUCCESSFUL -> MapPackDownloadStatus.State.DONE
                    DownloadManager.STATUS_FAILED -> MapPackDownloadStatus.State.FAILED
                    DownloadManager.STATUS_PAUSED -> when (cursor.getInt(reasonColumn)) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> MapPackDownloadStatus.State.WAITING_FOR_WIFI
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK, DownloadManager.PAUSED_WAITING_TO_RETRY ->
                            MapPackDownloadStatus.State.WAITING_FOR_NETWORK
                        else -> MapPackDownloadStatus.State.QUEUED
                    }
                    else -> MapPackDownloadStatus.State.QUEUED
                }
                found[id] = MapPackDownloadStatus(id, state, cursor.getLong(soFarColumn).coerceAtLeast(0), cursor.getLong(totalColumn))
            }
        }
        return ids.map { found[it] ?: MapPackDownloadStatus(it, MapPackDownloadStatus.State.GONE, 0, -1) }
    }

    override fun cancel(ids: List<Long>) {
        if (ids.isNotEmpty()) downloads.remove(*ids.toLongArray())
    }
}
