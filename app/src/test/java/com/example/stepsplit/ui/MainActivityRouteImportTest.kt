package com.example.stepsplit.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.example.stepsplit.StepSplitApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * A GPX file opened with the app ("Open with") or shared to it arrives at [MainActivity] (through the
 * manifest's `ImportRouteActivity`) as a one-shot import, like the notification's navigation - and
 * the manifest offers the app for GPX files without offering it for every file.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityRouteImportTest {

    private val app: StepSplitApplication = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null

    private val fileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fhike.gpx")

    @After
    fun tearDown() {
        controller?.destroy()
        app.container.database.close()
    }

    @Test
    fun `the file is read from an Open with's data or a Share's stream`() {
        assertEquals(fileUri, MainActivity.routeImportUri(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, "application/gpx+xml")))
        assertEquals(fileUri, MainActivity.routeImportUri(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, fileUri)))
        // Some apps put a shared file only in the clip data.
        val clipOnly = Intent(Intent.ACTION_SEND).setType("application/octet-stream").apply { clipData = ClipData.newRawUri("", fileUri) }
        assertEquals(fileUri, MainActivity.routeImportUri(clipOnly))
    }

    @Test
    fun `nothing to import from other intents, or a file that isn't a content address`() {
        assertNull(MainActivity.routeImportUri(null))
        assertNull(MainActivity.routeImportUri(Intent(Intent.ACTION_MAIN)))
        assertNull(MainActivity.routeImportUri(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRIPS)))
        assertNull(MainActivity.routeImportUri(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/Download/hike.gpx"), "application/gpx+xml")))
        assertNull(MainActivity.routeImportUri(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")))
    }

    @Test
    fun `opening a file imports it once, not again when the activity is recreated`() {
        // Created only, not shown: showing it would open the Map tab, and the map's native code
        // doesn't run on the JVM.
        val opening = Intent(Intent.ACTION_VIEW).setClass(app, MainActivity::class.java).setDataAndType(fileUri, "application/octet-stream")
        controller = Robolectric.buildActivity(MainActivity::class.java, opening).create()
        val activity = controller!!.get()
        assertEquals(fileUri, activity.routeImportEventForTest?.uri)

        // Recreated (a rotation, process death): the same launch intent, with saved state.
        val recreated = Robolectric.buildActivity(MainActivity::class.java, opening).create(Bundle()).get()
        assertNull(recreated.routeImportEventForTest)

        // A later share while the app runs is a new import.
        val first = activity.routeImportEventForTest!!
        val shared = Uri.parse("content://com.example.messages/item/42")
        controller!!.newIntent(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, shared))
        assertEquals(shared, activity.routeImportEventForTest?.uri)
        assertTrue(activity.routeImportEventForTest!!.id != first.id)
        // Anything else leaves a pending import as it is.
        controller!!.newIntent(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TRIPS))
        assertEquals(shared, activity.routeImportEventForTest?.uri)
    }

    private fun opensWithApp(intent: Intent): Boolean =
        app.packageManager.queryIntentActivities(intent.setPackage(app.packageName), 0)
            .any { it.activityInfo.name == "com.example.stepsplit.ui.ImportRouteActivity" }

    private fun view(uri: String, type: String) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), type).addCategory(Intent.CATEGORY_DEFAULT)

    private fun share(type: String) = Intent(Intent.ACTION_SEND).setType(type).addCategory(Intent.CATEGORY_DEFAULT)

    @Test
    fun `Open with offers the app for GPX types, whatever the address`() {
        assertTrue(opensWithApp(view("content://com.example.mail/attachment/7", "application/gpx+xml")))
        assertTrue(opensWithApp(view("content://com.example.mail/attachment/7", "application/x-gpx+xml")))
    }

    @Test
    fun `Open with offers the app for unknown and XML types only when the address ends in gpx`() {
        assertTrue(opensWithApp(view("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fhike.gpx", "application/octet-stream")))
        assertTrue(opensWithApp(view("content://com.example.files/root/My.Trips/day.2.hike.GPX", "text/xml")))
        assertTrue(opensWithApp(view("content://com.example.files/a.b/c.d/e.f/g.gpx", "application/octet-stream")))
        assertTrue(opensWithApp(view("content://com.example.files/hike.gpx", "text/plain")))
        assertFalse(opensWithApp(view("content://com.android.providers.downloads.documents/document/msf%3A27", "application/octet-stream")))
        assertFalse(opensWithApp(view("content://com.example.files/archive.zip", "application/octet-stream")))
        assertFalse(opensWithApp(view("content://com.example.files/notes.txt", "text/plain")))
        assertFalse(opensWithApp(view("content://com.example.files/photo.jpg", "image/jpeg")))
        // Only content addresses: a file address couldn't be read without a storage permission.
        assertFalse(opensWithApp(view("file:///sdcard/Download/hike.gpx", "application/octet-stream")))
        assertFalse(opensWithApp(view("https://example.com/hike.gpx", "application/gpx+xml")))
    }

    @Test
    fun `Share offers the app for GPX, unknown and XML types, never for text, images or KML`() {
        assertTrue(opensWithApp(share("application/gpx+xml")))
        assertTrue(opensWithApp(share("application/octet-stream")))
        assertTrue(opensWithApp(share("text/xml")))
        assertFalse(opensWithApp(share("text/plain")))
        assertFalse(opensWithApp(share("image/jpeg")))
        assertFalse(opensWithApp(share("application/vnd.google-earth.kml+xml")))
    }
}
