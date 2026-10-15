package com.example.stepsplit.ui.trips

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.LabeledIntent
import androidx.core.content.FileProvider
import com.example.stepsplit.R
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sends a trip's GPX file to another app (WhatsApp, Gmail, Telegram, Drive, a GPX app...) through
 * the system share sheet, which also offers "Save file" ([SaveGpxActivity]). To share, the app
 * has to own the file and hand out a temporary read grant, so the file is written to the app's
 * private cache and offered as a `content://` Uri through [FileProvider]. Nothing leaves the phone
 * until the user picks an app in the sheet, and only that one file is shared.
 */
internal object GpxShare {
    /** The cache subfolder the FileProvider exposes. Must match `res/xml/gpx_share_paths.xml`. */
    const val CACHE_DIRECTORY = "shared-gpx"
    const val MIME_TYPE = "application/gpx+xml"

    /** How long a shared file is kept after it was written before a later share removes it. */
    val STALE_AFTER_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * Writes [gpx] as [fileName] into a folder of its own for [tripId], under [cacheDir]. Two trips
     * from the same day get the same file name, so a shared folder per trip keeps one trip's share
     * from overwriting another's while the receiving app may still be reading it.
     *
     * First removes the folders of earlier shares written more than [STALE_AFTER_MILLIS] ago. A
     * receiving app can read the file some time after the share sheet closes (a Gmail draft, a slow
     * upload), so recent shares are kept; older ones go, so the cache doesn't grow with every share.
     */
    fun writeFile(
        cacheDir: File,
        tripId: Long,
        fileName: String,
        gpx: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): File {
        val root = File(cacheDir, CACHE_DIRECTORY)
        root.listFiles()?.forEach { folder ->
            if (nowMillis - folder.lastModified() > STALE_AFTER_MILLIS) folder.deleteRecursively()
        }
        val folder = File(root, "trip-$tripId")
        if (!folder.isDirectory && !folder.mkdirs()) error("Unable to create $folder")
        val file = File(folder, fileName)
        file.writeText(gpx, Charsets.UTF_8)
        // Overwriting a file that already exists doesn't touch its folder's time, so set it here:
        // the cleanup above goes by the folder's time.
        folder.setLastModified(nowMillis)
        return file
    }

    /**
     * The share sheet for [file], with [subject] as the subject for apps that use one (email).
     * The read grant travels with the chosen app's intent and covers only this file.
     *
     * Its first entry is this app's own "Save file", which opens [SaveGpxActivity] on the same
     * file. It is passed as an initial intent, which the share sheet has supported since Android's
     * first versions. Recent Android sheets draw it with the app's own icon and name, and this
     * label under them, so the label is kept short.
     */
    fun chooserIntent(context: Context, file: File, subject: String, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            // The ClipData carries the read grant into the chooser, and lets the sheet show the file name.
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val saveFile = LabeledIntent(
            Intent(context, SaveGpxActivity::class.java).setData(uri),
            context.packageName,
            context.getString(R.string.trip_gpx_save_file),
            R.drawable.ic_save_file,
        )
        return Intent.createChooser(send, chooserTitle).apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Intent>(saveFile))
        }
    }
}
