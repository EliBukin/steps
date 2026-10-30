package com.example.stepsplit.data.mappack

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Reads the manifest's text from the download server; a fake in tests. */
fun interface MapPackManifestSource {
    /** The manifest's JSON text; throws [IOException] when it can't be read. */
    fun fetch(url: String): String
}

/** A plain HTTPS GET: no cookies, no account, no app data in the request. */
object HttpMapPackManifestSource : MapPackManifestSource {
    override fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            // The manifest is a few kilobytes; anything far bigger isn't one.
            val bytes = connection.inputStream.use { it.readNBytesCompat(1 shl 20) }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
