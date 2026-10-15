package com.example.stepsplit.ui.trips

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class SaveGpxActivityTest {

    private lateinit var context: Context
    private lateinit var shared: Uri
    private val gpx = "<?xml version=\"1.0\"?><gpx><trk/></gpx>\n"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, GpxShare.CACHE_DIRECTORY).deleteRecursively()
        clearFileProviderCache()
        // Serves the shared file to the activity's ContentResolver, as the real FileProvider does.
        registerFileProvider(context)
        val file = GpxShare.writeFile(context.cacheDir, tripId = 4, fileName = "steps-gps-route-trip-2026-10-15.gpx", gpx = gpx)
        shared = FileProvider.getUriForFile(context, GpxShare.authority(context), file)
    }

    private fun start(data: Uri): ActivityController<SaveGpxActivity> =
        Robolectric.buildActivity(SaveGpxActivity::class.java, Intent(context, SaveGpxActivity::class.java).setData(data)).setup()

    /** Runs the copy (on Dispatchers.IO) and the main-thread work after it, until the activity closes. */
    private fun awaitFinish(activity: Activity) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `opens the save-as picker for a GPX file named like the shared one`() {
        val activity = start(shared).get()

        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.action)
        assertEquals("application/gpx+xml", picker.type)
        assertEquals("steps-gps-route-trip-2026-10-15.gpx", picker.getStringExtra(Intent.EXTRA_TITLE))
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `copies the shared file to the picked location, says so and closes`() {
        val activity = start(shared).get()
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        val destination = File(context.filesDir, "picked.gpx")

        shadowOf(activity).receiveResult(picker, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
        awaitFinish(activity)

        assertEquals(gpx, destination.readText())
        assertEquals("GPX file saved successfully", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `closes without writing anything when the picker is cancelled`() {
        val activity = start(shared).get()
        val picker = shadowOf(activity).nextStartedActivityForResult.intent

        shadowOf(activity).receiveResult(picker, Activity.RESULT_CANCELED, null)

        assertTrue(activity.isFinishing)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `ignores a file that is not from this app's FileProvider`() {
        val activity = start(Uri.parse("content://some.other.app.files/secret.gpx")).get()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
    }
}
