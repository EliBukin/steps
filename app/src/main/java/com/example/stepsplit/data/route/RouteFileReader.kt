package com.example.stepsplit.data.route

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What reading a file the owner opened with the app, shared to it, or picked with Import gave. */
sealed interface RouteFileRead {
    data class Read(val file: RouteFile) : RouteFileRead

    /** Over [ImportedRouteRepository.MAX_FILE_BYTES]; reading stopped there. */
    data object TooBig : RouteFileRead

    data object CantRead : RouteFileRead
}

fun interface RouteFileReader {
    suspend fun read(uri: Uri): RouteFileRead
}

/**
 * Reads a `content://` file through Android's [android.content.ContentResolver], with the one-off
 * read access the app that opened or shared it (or the system file picker) gave - no storage
 * permission. Only that one file is read, and only up to [ImportedRouteRepository.MAX_FILE_BYTES].
 */
class ContentResolverRouteFileReader(context: Context) : RouteFileReader {
    private val contentResolver = context.applicationContext.contentResolver

    override suspend fun read(uri: Uri): RouteFileRead = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") return@withContext RouteFileRead.CantRead
        try {
            val input = contentResolver.openInputStream(uri) ?: return@withContext RouteFileRead.CantRead
            val bytes = input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > ImportedRouteRepository.MAX_FILE_BYTES) return@withContext RouteFileRead.TooBig
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            RouteFileRead.Read(RouteFile(bytes, displayName(uri)))
        } catch (_: IOException) {
            RouteFileRead.CantRead
        } catch (_: SecurityException) {
            RouteFileRead.CantRead
        } catch (_: IllegalArgumentException) {
            RouteFileRead.CantRead
        }
    }

    /** The file's name where it came from, when that app says. */
    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }
}
