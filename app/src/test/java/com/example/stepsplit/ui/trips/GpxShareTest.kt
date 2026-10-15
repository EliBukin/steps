package com.example.stepsplit.ui.trips

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GpxShareTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = context.cacheDir
        File(cacheDir, GpxShare.CACHE_DIRECTORY).deleteRecursively()
        clearFileProviderCache()
    }

    private fun sharedRoot() = File(cacheDir, GpxShare.CACHE_DIRECTORY)

    @Test
    fun `writes the GPX into the trip's own folder in the shared cache directory`() {
        val file = GpxShare.writeFile(cacheDir, tripId = 7, fileName = "trip.gpx", gpx = "<gpx/>", nowMillis = now)

        assertEquals(File(sharedRoot(), "trip-7/trip.gpx"), file)
        assertEquals("<gpx/>", file.readText())
    }

    @Test
    fun `two trips with the same file name never overwrite each other`() {
        val first = GpxShare.writeFile(cacheDir, tripId = 1, fileName = "same-day.gpx", gpx = "first", nowMillis = now)
        val second = GpxShare.writeFile(cacheDir, tripId = 2, fileName = "same-day.gpx", gpx = "second", nowMillis = now)

        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
    }

    @Test
    fun `sharing the same trip again replaces its file and renews its folder's time`() {
        GpxShare.writeFile(cacheDir, tripId = 3, fileName = "trip.gpx", gpx = "old", nowMillis = now)
        val file = GpxShare.writeFile(cacheDir, tripId = 3, fileName = "trip.gpx", gpx = "new", nowMillis = now + 20 * hour)

        assertEquals("new", file.readText())
        assertEquals(now + 20 * hour, file.parentFile!!.lastModified())
    }

    @Test
    fun `a share removes earlier shares older than a day and keeps recent ones`() {
        val stale = GpxShare.writeFile(cacheDir, tripId = 1, fileName = "a.gpx", gpx = "a", nowMillis = now)
        val recent = GpxShare.writeFile(cacheDir, tripId = 2, fileName = "b.gpx", gpx = "b", nowMillis = now + 10 * hour)

        GpxShare.writeFile(cacheDir, tripId = 3, fileName = "c.gpx", gpx = "c", nowMillis = now + 25 * hour)

        assertFalse(stale.parentFile!!.exists())
        assertTrue(recent.exists())
        assertEquals(setOf("trip-2", "trip-3"), sharedRoot().list()!!.toSet())
    }

    @Test
    fun `the share sheet sends the file as GPX through this app's FileProvider with a read grant`() {
        val file = GpxShare.writeFile(cacheDir, tripId = 9, fileName = "steps-gps-route-trip-2026-10-15.gpx", gpx = "<gpx/>", nowMillis = now)

        val chooser = GpxShare.chooserIntent(context, file, subject = "GPS route from 15/10/2026", chooserTitle = "Share GPX file")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share GPX file", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/gpx+xml", send.type)
        assertEquals("GPS route from 15/10/2026", send.getStringExtra(Intent.EXTRA_SUBJECT))

        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals("steps-gps-route-trip-2026-10-15.gpx", uri.lastPathSegment)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The chooser itself must carry the grant too, or the picked app can't open the file.
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `the share sheet's first entry is Save file, which opens SaveGpxActivity on the same file`() {
        val file = GpxShare.writeFile(cacheDir, tripId = 9, fileName = "steps-gps-route-trip-2026-10-15.gpx", gpx = "<gpx/>", nowMillis = now)

        val chooser = GpxShare.chooserIntent(context, file, subject = "subject", chooserTitle = "title")

        @Suppress("DEPRECATION") // The typed overload needs API 33; this runs on every version.
        val initial = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!
        assertEquals(1, initial.size)
        val save = initial[0] as LabeledIntent
        assertEquals("Save file", save.nonLocalizedLabel)
        assertEquals(context.packageName, save.sourcePackage)
        assertEquals(R.drawable.ic_save_file, save.iconResource)
        assertEquals(ComponentName(context, SaveGpxActivity::class.java), save.component)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java), save.data)
    }
}
