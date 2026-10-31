package com.example.stepsplit.data.mappack

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
        assertEquals(listOf(pack), repository.installedPacks.value)
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
        advanceTimeBy(1_500)
        assertEquals(emptyList<Throwable>(), escaped)
        assertEquals(MapPacksState.Problem.DOWNLOAD_FAILED, repository.state.value.problem)
        assertNull(repository.state.value.download)
        assertNull(MapPackStore(root).readPendingDownload())
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
