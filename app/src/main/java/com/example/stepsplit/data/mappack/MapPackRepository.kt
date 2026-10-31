package com.example.stepsplit.data.mappack

import android.content.Context
import androidx.core.content.edit
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the Offline maps settings show; see [MapPackRepository]. */
data class MapPacksState(
    val manifest: ManifestStatus = ManifestStatus.NOT_CHECKED,
    /** The regions on the server (or as last seen there), newest manifest first. */
    val regions: List<MapPackRegion> = emptyList(),
    val installed: List<InstalledMapPack> = emptyList(),
    val download: DownloadProgress? = null,
    val problem: Problem? = null,
    val wifiOnly: Boolean = true,
    /** The Map tab's offer of an offline map was answered, or a pack was downloaded: don't offer again. */
    val offerAnswered: Boolean = false,
) {
    enum class ManifestStatus { NOT_CHECKED, CHECKING, CHECKED, FAILED, NEEDS_NEWER_APP }

    enum class Problem { DOWNLOAD_FAILED, CHECK_FAILED, NOT_ENOUGH_SPACE, NO_STORAGE }

    fun installed(regionId: String) = installed.firstOrNull { it.regionId == regionId }
}

data class DownloadProgress(
    val regionId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val waiting: Waiting? = null,
    /** Every file is down; their SHA-256 is being checked. */
    val checking: Boolean = false,
) {
    enum class Waiting { WIFI, NETWORK }
}

/** The Offline maps settings' choices, kept apart from the packs' own folder. */
interface MapPackPreferences {
    var wifiOnly: Boolean

    /** See [MapPacksState.offerAnswered]. */
    var offerAnswered: Boolean
}

class SharedPreferencesMapPackPreferences(context: Context) : MapPackPreferences {
    private val prefs = context.getSharedPreferences("map_packs", Context.MODE_PRIVATE)
    override var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit { putBoolean("wifi_only", value) }
    override var offerAnswered: Boolean
        get() = prefs.getBoolean("offer_answered", false)
        set(value) = prefs.edit { putBoolean("offer_answered", value) }
}

/**
 * Offline map packs: what the server offers, what's on the phone, and the download in between.
 *
 * Nothing here runs on its own schedule: the manifest is read only when the Offline maps settings
 * open ([refreshManifest]), and files download only when the user taps Download. A download keeps
 * going in Android's download manager if the app closes; the next time the app starts, the
 * finished files are checked and installed (or the progress picked up again).
 *
 * [store] is null when the phone's storage isn't available; then nothing can be downloaded and no
 * pack is installed.
 *
 * Nothing that goes wrong here may end the app: [scope] lives as long as the process, so an
 * exception that got out would crash it, and a pending download that keeps failing would crash it
 * again after every start. Each launched block catches what it can't handle, tidies up (the
 * pending record first) and says so in [MapPacksState.problem].
 */
class MapPackRepository(
    private val store: MapPackStore?,
    private val downloader: MapPackDownloader,
    private val manifestSource: MapPackManifestSource,
    private val manifestUrl: () -> String,
    private val preferences: MapPackPreferences,
    private val appVersionCode: Int,
    /** The download notification's title for a region, e.g. "Israel map". */
    private val downloadTitle: (regionId: String) -> String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Bytes a download may still use in a folder (see [allocatableBytes]). */
    private val freeBytes: (File) -> Long,
    private val pollMillis: Long = 1_000,
) {
    private val _state = MutableStateFlow(MapPacksState(wifiOnly = preferences.wifiOnly, offerAnswered = preferences.offerAnswered))
    val state: StateFlow<MapPacksState> = _state.asStateFlow()

    /** The packs the map draws from. */
    val installedPacks: StateFlow<List<InstalledMapPack>> =
        _state.map { it.installed }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mutex = Mutex()
    private var pollJob: Job? = null

    init {
        scope.launch {
            try {
                withContext(io) {
                    val cached = store?.readCachedManifest()?.parse()
                    val installed = store?.installed().orEmpty()
                    _state.update { it.copy(installed = installed, regions = cached?.usableRegions().orEmpty()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing installed is shown; the packs are read again on the next start.
            }
            if (store?.readPendingDownload() != null) startPolling()
        }
    }

    /** Reads the server's manifest (the Offline maps settings opened, or Try again). */
    fun refreshManifest() {
        scope.launch {
            _state.update { it.copy(manifest = MapPacksState.ManifestStatus.CHECKING) }
            val fetched = withContext(io) {
                runCatching {
                    val url = manifestUrl()
                    CachedManifest(manifestSource.fetch(url), url)
                }.getOrNull()
            }
            val manifest = fetched?.parse()
            _state.update {
                when {
                    manifest == null -> it.copy(manifest = MapPacksState.ManifestStatus.FAILED)
                    manifest.schema > MapPackManifest.SCHEMA -> it.copy(manifest = MapPacksState.ManifestStatus.NEEDS_NEWER_APP)
                    else -> it.copy(manifest = MapPacksState.ManifestStatus.CHECKED, regions = manifest.usableRegions())
                }
            }
            if (fetched != null && manifest != null && manifest.schema <= MapPackManifest.SCHEMA) {
                // Only so the sizes show with no internet next time: not being able to keep it is no failure.
                withContext(io) { runCatching { store?.writeCachedManifest(fetched) } }
            }
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        preferences.wifiOnly = wifiOnly
        _state.update { it.copy(wifiOnly = wifiOnly) }
    }

    /** Downloads [regionId] as the last manifest read lists it (a first download or an update). */
    fun download(regionId: String) {
        scope.launch {
            mutex.withLock {
                if (_state.value.download != null) return@withLock
                val store = store ?: return@withLock _state.update { it.copy(problem = MapPacksState.Problem.NO_STORAGE) }
                val manifestText = withContext(io) { currentManifestText() } ?: return@withLock
                val region = manifestText.parse()?.regions?.firstOrNull { it.id == regionId }
                if (region == null || !region.isComplete || region.minAppVersionCode > appVersionCode) return@withLock
                val downloadDir = store.downloadDir(region.id, region.version)
                // The new files sit next to the installed version until they're checked: room for both.
                val needed = region.totalBytes + SPARE_BYTES
                if (withContext(io) { freeBytes(store.root.apply { mkdirs() }) } < needed) {
                    return@withLock _state.update { it.copy(problem = MapPacksState.Problem.NOT_ENOUGH_SPACE) }
                }
                val ids = mutableListOf<Long>()
                try {
                    withContext(io) {
                        downloadDir.deleteRecursively()
                        downloadDir.mkdirs()
                        region.files.forEach { file ->
                            ids += downloader.enqueue(file.url, File(downloadDir, file.name), downloadTitle(region.id), preferences.wifiOnly)
                        }
                        store.writePendingDownload(PendingDownload(region.id, region.version, ids, manifestText))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The download manager is switched off, or the folder can't be written.
                    withContext(io) { abandon(store, ids, downloadDir) }
                    return@withLock _state.update { it.copy(problem = MapPacksState.Problem.DOWNLOAD_FAILED) }
                }
                _state.update { it.copy(problem = null, download = DownloadProgress(region.id, 0, region.totalBytes)) }
            }
            startPolling()
        }
    }

    fun cancelDownload() {
        scope.launch {
            mutex.withLock {
                pollJob?.cancel()
                withContext(io) {
                    val pending = store?.readPendingDownload() ?: return@withContext
                    abandon(store, pending.downloadIds, store.downloadDir(pending.regionId, pending.version))
                }
                _state.update { it.copy(download = null) }
            }
        }
    }

    fun delete(regionId: String) {
        scope.launch {
            mutex.withLock {
                if (_state.value.download?.regionId == regionId) return@withLock
                val installed = withContext(io) {
                    runCatching { store?.delete(regionId) }
                    store?.installed().orEmpty()
                }
                _state.update { it.copy(installed = installed) }
            }
        }
    }

    fun dismissProblem() = _state.update { it.copy(problem = null) }

    /** The Map tab's offer of an offline map was answered (Download or Not now). */
    fun answerOffer() {
        preferences.offerAnswered = true
        _state.update { it.copy(offerAnswered = true) }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                val done = mutex.withLock { pollOnce() }
                if (done) break
                delay(pollMillis)
            }
        }
    }

    /**
     * One look at the download; true once it's over (installed, failed, or gone). Whatever goes
     * wrong ends the download with a problem and no pending record, so the next start doesn't
     * meet the same failure again.
     */
    private suspend fun pollOnce(): Boolean {
        val store = store ?: return true
        val pending = withContext(io) { store.readPendingDownload() } ?: return true.also { _state.update { it.copy(download = null) } }
        var checking = false
        return try {
            pollDownload(store, pending) { checking = true }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val installed = withContext(io) {
                abandon(store, pending.downloadIds, store.downloadDir(pending.regionId, pending.version))
                runCatching { store.installed() }.getOrNull()
            }
            _state.update {
                it.copy(
                    download = null,
                    installed = installed ?: it.installed,
                    problem = if (checking) MapPacksState.Problem.CHECK_FAILED else MapPacksState.Problem.DOWNLOAD_FAILED,
                )
            }
            true
        }
    }

    private suspend fun pollDownload(store: MapPackStore, pending: PendingDownload, onChecking: () -> Unit): Boolean {
        val region = pending.manifest.parse()?.regions?.firstOrNull { it.id == pending.regionId && it.version == pending.version }
        val statuses = withContext(io) { downloader.status(pending.downloadIds) }
        if (region == null || statuses.any { it.state == MapPackDownloadStatus.State.FAILED || it.state == MapPackDownloadStatus.State.GONE }) {
            withContext(io) { abandon(store, pending.downloadIds, store.downloadDir(pending.regionId, pending.version)) }
            _state.update { it.copy(download = null, problem = MapPacksState.Problem.DOWNLOAD_FAILED) }
            return true
        }
        if (statuses.all { it.state == MapPackDownloadStatus.State.DONE }) {
            onChecking()
            _state.update { it.copy(download = DownloadProgress(region.id, region.totalBytes, region.totalBytes, checking = true)) }
            val installed = withContext(io) {
                val pack = store.install(region)
                store.writePendingDownload(null)
                pack
            }
            if (installed != null) preferences.offerAnswered = true
            val all = withContext(io) { store.installed() }
            _state.update {
                it.copy(
                    download = null,
                    installed = all,
                    problem = if (installed == null) MapPacksState.Problem.CHECK_FAILED else null,
                    offerAnswered = it.offerAnswered || installed != null,
                )
            }
            return true
        }
        val waiting = when {
            statuses.any { it.state == MapPackDownloadStatus.State.RUNNING } -> null
            statuses.any { it.state == MapPackDownloadStatus.State.WAITING_FOR_WIFI } -> DownloadProgress.Waiting.WIFI
            statuses.any { it.state == MapPackDownloadStatus.State.WAITING_FOR_NETWORK } -> DownloadProgress.Waiting.NETWORK
            else -> null
        }
        _state.update {
            it.copy(download = DownloadProgress(region.id, statuses.sumOf { s -> s.downloadedBytes }, region.totalBytes, waiting))
        }
        return false
    }

    private fun currentManifestText(): CachedManifest? = store?.readCachedManifest()

    /**
     * Undoes a download that can't go on: its downloads in the download manager, its files, and
     * its pending record. Each step on its own, so one that fails doesn't stop the others.
     */
    private fun abandon(store: MapPackStore, downloadIds: List<Long>, downloadDir: File) {
        runCatching { downloader.cancel(downloadIds) }
        runCatching { downloadDir.deleteRecursively() }
        runCatching { store.writePendingDownload(null) }
    }

    /** The regions this app can draw: complete ones, in a manifest layout it knows. */
    private fun MapPackManifest.usableRegions() = if (schema > MapPackManifest.SCHEMA) emptyList() else regions.filter { it.isComplete }

    private companion object {
        /** Room left over after a download, so the phone doesn't fill up to the last byte. */
        const val SPARE_BYTES = 50L * 1024 * 1024
    }
}

/**
 * What a download may use in [dir]: free space plus cached files Android would clear to make room.
 * Unknown (the most there could be) when Android can't say; the download manager then stops by
 * itself if the space runs out.
 */
fun allocatableBytes(context: Context, dir: File): Long = runCatching {
    val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
    storage.getAllocatableBytes(storage.getUuidForPath(dir))
}.getOrDefault(Long.MAX_VALUE)
