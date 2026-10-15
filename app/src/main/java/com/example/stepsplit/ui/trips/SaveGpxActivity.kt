package com.example.stepsplit.ui.trips

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.stepsplit.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Save file" entry in the GPX share sheet (see [GpxShare.chooserIntent]). It has no screen
 * of its own: it opens the system "save as" picker for the shared GPX file in its [getIntent] data,
 * copies the file to wherever the user picks, shows the result and closes. The picker hands back a
 * location this app may write to, so no storage permission is needed.
 *
 * Only acts on a file from this app's own FileProvider; anything else closes it at once.
 */
class SaveGpxActivity : ComponentActivity() {

    private val source: Uri? get() = intent.data?.takeIf { it.authority == GpxShare.authority(this) }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(GpxShare.MIME_TYPE)) { destination ->
        val source = source
        if (destination == null || source == null) {
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { copy(source, destination) }.isSuccess }
            Toast.makeText(
                applicationContext,
                if (saved) R.string.trip_gpx_export_success else R.string.trip_gpx_export_failure,
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = source
        if (source == null) {
            finish()
            return
        }
        // After a configuration change or process death the picker is still open, and its result
        // comes back to the callback above - so only open it the first time.
        if (savedInstanceState == null) createDocument.launch(source.lastPathSegment ?: FALLBACK_FILE_NAME)
    }

    private fun copy(source: Uri, destination: Uri) {
        val input = contentResolver.openInputStream(source) ?: error("Unable to read $source")
        input.use {
            val output = contentResolver.openOutputStream(destination) ?: error("Unable to write $destination")
            output.use { input.copyTo(it) }
        }
    }

    private companion object {
        const val FALLBACK_FILE_NAME = "trip.gpx"
    }
}
