package com.example.stepsplit.data.mappack

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MapPackRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File by lazy { File(folder.root, "maps") }
    private var manifestText = MapPackTestData.manifestJson()
    private var serverUp = true
    private var fetches = 0
    private val preferences = InMemoryMapPackPreferences()

    private fun TestScope.repository(
        downloader: MapPackDownloader = FakeMapPackDownloader(),
        freeBytes: Long = Long.MAX_VALUE,
        store: MapPackStore? = MapPackStore(root),
        // Its own scope, as the app's process has: advanceUntilIdle runs it (not so backgroundScope).
        scope: CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
    ) = MapPackRepository(
        store = store,
        downloader = downloader,
        manifestSource = { url ->
            fetches++
            if (!serverUp) throw IOException("offline")
            check(url == MapPackTestData.MANIFEST_URL)
            manifestText
        },
        manifestUrl = { MapPackTestData.MANIFEST_URL },
        preferences = preferences,
        appVersionCode = 1,
        downloadTitle = { "Israel map" },
        scope = scope,
        io = StandardTestDispatcher(testScheduler),
        freeBytes = { freeBytes },
    )

    @Test
    fun `nothing is read from the server until the settings ask`() = runTest {
        val repository = repository()
        advanceUntilIdle()
        assertEquals(0, fetches)
        assertEquals(MapPacksState.ManifestStatus.NOT_CHECKED, repository.state.value.manifest)

        repository.refreshManifest()
        advanceUntilIdle()
        assertEquals(1, fetches)
        assertEquals(MapPacksState.ManifestStatus.CHECKED, repository.state.value.manifest)
        assertEquals("israel", repository.state.value.regions.single().id)
    }

    @Test
    fun `with no connection the maps seen last time still show`() = runTest {
        repository().apply { refreshManifest() }
        advanceUntilIdle()

        serverUp = false
        val later = repository()
        later.refreshManifest()
        advanceUntilIdle()
        assertEquals(MapPacksState.ManifestStatus.FAILED, later.state.value.manifest)
        assertEquals("israel", later.state.value.regions.single().id)
    }

    @Test
    fun `a manifest for a newer app offers nothing`() = runTest {
        manifestText = MapPackTestData.manifestJson(schema = MapPackManifest.SCHEMA + 1)
        val repository = repository()
        repository.refreshManifest()
        advanceUntilIdle()
        assertEquals(MapPacksState.ManifestStatus.NEEDS_NEWER_APP, repository.state.value.manifest)
        assertTrue(repository.state.value.regions.isEmpty())
    }

    @Test
    fun `a download goes through the download manager, is checked, and becomes the map`() = runTest {
        val downloader = FakeMapPackDownloader()
        val repository = repository(downloader)
        repository.refreshManifest()
        advanceUntilIdle()

        repository.download("israel")
        advanceTimeBy(1)
        assertNotNull(repository.state.value.download)
        assertEquals(true, downloader.wifiOnlyRequested)
        advanceUntilIdle()

        val state = repository.state.value
        assertNull(state.download)
        assertNull(state.problem)
        val pack = state.installed.single()
        assertEquals("2026-10-29", pack.version)
        assertTrue(pack.vectorFile.readBytes().contentEquals(MapPackTestData.vectorBytes))
        assertTrue("downloading a pack answers the Map tab's offer", state.offerAnswered)
        assertEquals(2, downloader.enqueued.size)
    }

    @Test
    fun `a file that doesn't match its checksum is thrown away`() = runTest {
        manifestText = MapPackTestData.manifestJson(vectorSha = "0".repeat(64))
        val repository = repository()
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()

        assertEquals(MapPacksState.Problem.CHECK_FAILED, repository.state.value.problem)
        assertTrue(repository.state.value.installed.isEmpty())
        assertFalse(File(root, "israel/download-2026-10-29").exists())
    }

    @Test
    fun `a failed download says so and leaves nothing behind`() = runTest {
        val downloader = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING, MapPackDownloadStatus.State.FAILED))
        val repository = repository(downloader)
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()

        assertEquals(MapPacksState.Problem.DOWNLOAD_FAILED, repository.state.value.problem)
        assertNull(repository.state.value.download)
        assertEquals(2, downloader.cancelled.size)
        assertNull(MapPackStore(root).readPendingDownload())
    }

    @Test
    fun `cancel stops the download manager's downloads`() = runTest {
        val downloader = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.WAITING_FOR_WIFI))
        val repository = repository(downloader)
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceTimeBy(1_500)
        assertEquals(DownloadProgress.Waiting.WIFI, repository.state.value.download?.waiting)

        repository.cancelDownload()
        advanceTimeBy(100)
        assertNull(repository.state.value.download)
        assertEquals(2, downloader.cancelled.size)
        assertFalse(File(root, "israel/download-2026-10-29").exists())
    }

    @Test
    fun `no download starts without room for it`() = runTest {
        val downloader = FakeMapPackDownloader()
        val repository = repository(downloader, freeBytes = 1_000)
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()

        assertEquals(MapPacksState.Problem.NOT_ENOUGH_SPACE, repository.state.value.problem)
        assertTrue(downloader.enqueued.isEmpty())
    }

    @Test
    fun `without the phone's storage nothing downloads`() = runTest {
        val repository = repository(store = null)
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()
        assertEquals(MapPacksState.Problem.NO_STORAGE, repository.state.value.problem)
    }

    @Test
    fun `a download the app was closed during is installed when it starts again`() = runTest {
        val downloader = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING, MapPackDownloadStatus.State.DONE))
        val firstProcess = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        repository(downloader, scope = firstProcess).apply {
            refreshManifest()
            advanceUntilIdle()
            download("israel")
        }
        advanceTimeBy(1)
        // The app process ends here; the download manager carries on with the same downloads.
        firstProcess.cancel()
        assertNotNull(MapPackStore(root).readPendingDownload())
        val restarted = repository(downloader)
        advanceUntilIdle()
        assertEquals("israel", restarted.state.value.installed.single().regionId)
    }

    @Test
    fun `an update replaces the old version only once the new one is checked, then delete removes it`() = runTest {
        val repository = repository()
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()

        manifestText = MapPackTestData.manifestJson(version = "2027-01-15")
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()
        assertEquals("2027-01-15", repository.state.value.installed.single().version)
        assertFalse(File(root, "israel/2026-10-29").exists())

        repository.delete("israel")
        advanceUntilIdle()
        assertTrue(repository.state.value.installed.isEmpty())
        assertFalse(File(root, "israel").exists())
    }

    @Test
    fun `publishing the older pack again replaces a broken newer one`() = runTest {
        manifestText = MapPackTestData.manifestJson(version = "2026-10-30")
        val repository = repository()
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()

        manifestText = MapPackTestData.manifestJson(version = "2026-10-29")
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()
        assertEquals("2026-10-29", repository.state.value.installed.single().version)
        assertFalse(File(root, "israel/2026-10-30").exists())
    }

    // ---- Review 2, D4: each moment the app can end in during a download or an install, then the next start. ----

    private val old = "2026-10-29"
    private val new = "2027-01-15"

    /** [old] installed the normal way; the manifest now offers [new]. */
    private fun TestScope.installOldThenOfferNew() {
        val repository = repository()
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()
        assertEquals(old, repository.state.value.installed.single().version)
        manifestText = MapPackTestData.manifestJson(version = new)
        repository.refreshManifest()
        advanceUntilIdle()
    }

    /** Starts downloading [new] in a process that then ends, its files still on their way. */
    private fun TestScope.startNewDownloadAndEnd(downloader: MapPackDownloader) {
        val firstProcess = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        repository(downloader, scope = firstProcess).download("israel")
        advanceTimeBy(1)
        firstProcess.cancel()
        assertEquals(new, MapPackStore(root).readPendingDownload()!!.version)
    }

    private fun regionFolders() = File(root, "israel").listFiles { file -> file.isDirectory }.orEmpty().map { it.name }.toSet()

    @Test
    fun `kill point 1 - files moved into place but not recorded - the install is finished on the next start`() = runTest {
        installOldThenOfferNew()
        startNewDownloadAndEnd(FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING)))
        // The files came down, were checked and moved into place; the app ended before writing installed.json.
        val download = File(root, "israel/download-$new")
        File(download, "israel-$new-vector.pmtiles").writeBytes(MapPackTestData.vectorBytes)
        File(download, "israel-$new-dem.pmtiles").writeBytes(MapPackTestData.demBytes)
        assertTrue(download.renameTo(File(root, "israel/$new")))

        // The download manager may have forgotten those downloads by now: a fresh fake knows none of them.
        val restarted = repository(FakeMapPackDownloader())
        advanceUntilIdle()
        assertNull("no false problem", restarted.state.value.problem)
        assertNull(restarted.state.value.download)
        assertEquals(new, restarted.state.value.installed.single().version)
        assertEquals(setOf(new), regionFolders())
        assertNull(MapPackStore(root).readPendingDownload())
    }

    @Test
    fun `kill point 2 - recorded but the old version not deleted - the old version goes on the next start`() = runTest {
        installOldThenOfferNew()
        val oldFiles = File(folder.root, "old-copy").also { File(root, "israel/$old").copyRecursively(it) }
        val repository = repository()
        repository.download("israel")
        advanceUntilIdle()
        // The app ended after writing installed.json for the new version, before deleting the old one.
        oldFiles.copyRecursively(File(root, "israel/$old"))
        MapPackStore(root).writePendingDownload(PendingDownload("israel", new, listOf(102L, 103L), MapPackStore(root).readCachedManifest()!!))

        val restarted = repository()
        advanceUntilIdle()
        assertNull(restarted.state.value.problem)
        assertEquals(new, restarted.state.value.installed.single().version)
        assertEquals(setOf(new), regionFolders())
        assertNull(MapPackStore(root).readPendingDownload())
    }

    // Kill point 3, between handing a file to the download manager and recording its id, is
    // `a download the app ended in the middle of handing over is cancelled on the next start`.

    @Test
    fun `kill point 4 - Download tapped before the first look at a download that goes on - nothing new starts`() = runTest {
        installOldThenOfferNew()
        val fake = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING, MapPackDownloadStatus.State.DONE))
        startNewDownloadAndEnd(fake)

        // A Settings screen restored at launch: a tap lands before the first look fills in the progress.
        val restarted = repository(fake)
        restarted.download("israel")
        advanceUntilIdle()
        assertEquals("no second download replaces the record", 2, fake.enqueued.size)
        assertTrue("the first one isn't orphaned", fake.cancelled.isEmpty())
        assertNull(restarted.state.value.problem)
        assertEquals(new, restarted.state.value.installed.single().version)
        assertEquals(setOf(new), regionFolders())
    }

    @Test
    fun `at startup only the installed version and the download on its way keep their folders`() = runTest {
        installOldThenOfferNew()
        val fake = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING))
        startNewDownloadAndEnd(fake)
        // Left behind by earlier versions of the app, or by downloads and installs it ended in the middle of.
        File(root, "israel/download-2026-05-01").mkdirs()
        File(root, "israel/2026-05-01").mkdirs()
        File(root, "israel/2026-05-01/israel-2026-05-01-vector.pmtiles").writeBytes(MapPackTestData.vectorBytes)
        File(root, "elsewhere/download-2026-05-01").mkdirs()

        val restarted = repository(fake)
        advanceTimeBy(1)
        assertEquals(setOf(old, "download-$new"), regionFolders())
        assertFalse(File(root, "elsewhere/download-2026-05-01").exists())
        assertEquals(old, restarted.state.value.installed.single().version)
        assertNotNull(restarted.state.value.download)
        restarted.cancelDownload()
        advanceUntilIdle()
    }

    @Test
    fun `a region whose installed record can't be read is left alone at startup`() = runTest {
        installOldThenOfferNew()
        File(root, "israel/installed.json").writeText("{ not json")
        File(root, "israel/download-2026-05-01").mkdirs()

        repository()
        advanceUntilIdle()
        assertEquals(setOf(old, "download-2026-05-01"), regionFolders())
    }

    /** The app's map-pack scope with a handler that records, instead of a crash, anything that gets out. */
    private fun TestScope.processScope(escaped: MutableList<Throwable>) =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e })

    @Test
    fun `an install that throws ends in a problem, with no pending record to fail again on the next start`() = runTest {
        val escaped = mutableListOf<Throwable>()
        val regionDir = File(root, "israel")
        val fake = FakeMapPackDownloader()
        // Once every file is down, the region's folder can't be written: moving the files into place throws.
        val downloader = object : MapPackDownloader by fake {
            override fun status(ids: List<Long>) = fake.status(ids).also { statuses ->
                if (statuses.all { it.state == MapPackDownloadStatus.State.DONE }) regionDir.setWritable(false)
            }
        }
        try {
            val repository = repository(downloader, scope = processScope(escaped))
            repository.refreshManifest()
            advanceUntilIdle()
            repository.download("israel")
            advanceUntilIdle()

            assertEquals(emptyList<Throwable>(), escaped)
            assertEquals(MapPacksState.Problem.CHECK_FAILED, repository.state.value.problem)
            assertNull("not stuck on checking", repository.state.value.download)
            assertTrue(repository.state.value.installed.isEmpty())
            assertFalse(File(root, "download.json").exists())
        } finally {
            regionDir.setWritable(true)
        }

        val restarted = repository(fake, scope = processScope(escaped))
        advanceUntilIdle()
        assertEquals(emptyList<Throwable>(), escaped)
        assertNull(restarted.state.value.download)
    }

    @Test
    fun `a download manager that throws ends in a problem, and what it had started is cancelled`() = runTest {
        val escaped = mutableListOf<Throwable>()
        val fake = FakeMapPackDownloader()
        // The download manager is switched off after the first file was handed to it.
        val downloader = object : MapPackDownloader by fake {
            override fun enqueue(url: String, destination: File, title: String, wifiOnly: Boolean): Long {
                if (fake.enqueued.isNotEmpty()) throw IllegalArgumentException("Unknown URL content://downloads/my_downloads")
                return fake.enqueue(url, destination, title, wifiOnly)
            }
        }
        val repository = repository(downloader, scope = processScope(escaped))
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()

        assertEquals(emptyList<Throwable>(), escaped)
        assertEquals(MapPacksState.Problem.DOWNLOAD_FAILED, repository.state.value.problem)
        assertNull(repository.state.value.download)
        assertEquals(listOf(100L), fake.cancelled)
        assertNull(MapPackStore(root).readPendingDownload())
        assertFalse(File(root, "israel/download-2026-10-29").exists())
    }

    @Test
    fun `a download manager that can't be asked ends the download, even when cancelling throws too`() = runTest {
        val escaped = mutableListOf<Throwable>()
        val fake = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING))
        var broken = false
        val downloader = object : MapPackDownloader by fake {
            override fun status(ids: List<Long>) = if (broken) throw SecurityException("no access") else fake.status(ids)
            override fun cancel(ids: List<Long>) = if (broken) throw SecurityException("no access") else fake.cancel(ids)
        }
        val repository = repository(downloader, scope = processScope(escaped))
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceTimeBy(1_500)
        assertNotNull(repository.state.value.download)

        broken = true
        advanceTimeBy(61_000)
        assertEquals(emptyList<Throwable>(), escaped)
        assertEquals(MapPacksState.Problem.DOWNLOAD_FAILED, repository.state.value.problem)
        assertNull(repository.state.value.download)
        assertNull(MapPackStore(root).readPendingDownload())
    }

    @Test
    fun `the download's record is written before each file goes to the download manager`() = runTest {
        val fake = FakeMapPackDownloader()
        val recordedBeforeEach = mutableListOf<List<Long>?>()
        val downloader = object : MapPackDownloader by fake {
            override fun enqueue(url: String, destination: File, title: String, wifiOnly: Boolean): Long {
                recordedBeforeEach += MapPackStore(root).readPendingDownload()?.downloadIds
                return fake.enqueue(url, destination, title, wifiOnly)
            }
        }
        val repository = repository(downloader)
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceTimeBy(1)

        assertEquals(listOf(emptyList(), listOf(100L)), recordedBeforeEach)
        assertEquals(listOf(100L, 101L), MapPackStore(root).readPendingDownload()!!.downloadIds)
    }

    @Test
    fun `a download the app ended in the middle of handing over is cancelled on the next start`() = runTest {
        val fake = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING))
        val firstProcess = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        repository(fake, scope = firstProcess).apply {
            refreshManifest()
            advanceUntilIdle()
            download("israel")
        }
        advanceTimeBy(1)
        firstProcess.cancel()
        // The app ended right after the first of the two files was handed over: its record has one id.
        val store = MapPackStore(root)
        store.writePendingDownload(store.readPendingDownload()!!.copy(downloadIds = listOf(100L)))

        val restarted = repository(fake)
        advanceUntilIdle()
        assertEquals(MapPacksState.Problem.DOWNLOAD_FAILED, restarted.state.value.problem)
        assertNull(restarted.state.value.download)
        assertNull(store.readPendingDownload())
        // Kill point 3 (review 2, D4): the second file's download, which the record doesn't know, is removed as well.
        assertEquals(setOf(100L, 101L), fake.cancelled.toSet())
        assertTrue(fake.unfinished().isEmpty())
        assertFalse(File(root, "israel/download-2026-10-29").exists())
    }

    @Test
    fun `a download the app ended in the middle of handing over is removed on the next start even while it is still queued`() = runTest {
        // The download manager hasn't started either file: a queued download has no local file yet.
        val fake = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.QUEUED))
        val firstProcess = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        repository(fake, scope = firstProcess).apply {
            refreshManifest()
            advanceUntilIdle()
            download("israel")
        }
        advanceTimeBy(1)
        firstProcess.cancel()
        // The app ended after handing over the second file, before recording its id.
        val store = MapPackStore(root)
        store.writePendingDownload(store.readPendingDownload()!!.copy(downloadIds = listOf(100L)))
        assertEquals(listOf(null, null), fake.unfinished().map { it.file })

        val restarted = repository(fake)
        advanceUntilIdle()
        // Left queued, it would later land in a pack folder made again.
        assertEquals(emptyList<UnfinishedDownload>(), fake.unfinished())
        assertEquals(setOf(100L, 101L), fake.cancelled.toSet())
        assertNull(restarted.state.value.download)
        assertNull(store.readPendingDownload())
    }

    @Test
    fun `a pack installed just before the app ended is not reported as failed on the next start`() = runTest {
        val repository = repository()
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceUntilIdle()
        val pending = PendingDownload("israel", "2026-10-29", listOf(100L, 101L), MapPackStore(root).readCachedManifest()!!)
        // The app ended between installing the pack and clearing its record.
        MapPackStore(root).writePendingDownload(pending)

        val restarted = repository()
        advanceUntilIdle()
        assertNull(restarted.state.value.problem)
        assertNull(restarted.state.value.download)
        assertEquals("2026-10-29", restarted.state.value.installed.single().version)
        assertNull(MapPackStore(root).readPendingDownload())
    }

    @Test
    fun `a download is asked about every second on screen, and once a minute with the app in the background`() = runTest {
        val fake = FakeMapPackDownloader(script = listOf(MapPackDownloadStatus.State.RUNNING))
        var looks = 0
        val downloader = object : MapPackDownloader by fake {
            override fun status(ids: List<Long>) = fake.status(ids).also { looks++ }
        }
        val repository = repository(downloader)
        repository.refreshManifest()
        advanceUntilIdle()
        repository.download("israel")
        advanceTimeBy(1)
        assertEquals(1, looks)

        advanceTimeBy(30_000)
        assertEquals("nobody looks", 1, looks)
        // A screen shows the download: asked at once, then every second.
        val screen = launch(UnconfinedTestDispatcher(testScheduler)) { repository.state.collect {} }
        advanceTimeBy(10_500)
        assertTrue("looks $looks", looks in 11..13)

        screen.cancel()
        val before = looks
        advanceTimeBy(119_000)
        assertTrue("looks ${looks - before}", looks - before in 2..3)
        repository.cancelDownload()
        advanceUntilIdle()
    }

    @Test
    fun `the Wi-Fi choice and the Map tab's offer are remembered`() = runTest {
        val repository = repository()
        repository.setWifiOnly(false)
        repository.answerOffer()
        assertFalse(preferences.wifiOnly)
        assertTrue(preferences.offerAnswered)
        assertTrue(repository().state.value.offerAnswered)
        assertFalse(repository().state.value.wifiOnly)
    }
}
