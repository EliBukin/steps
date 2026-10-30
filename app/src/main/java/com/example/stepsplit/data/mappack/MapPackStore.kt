package com.example.stepsplit.data.mappack

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A downloaded, checked map pack the map can draw from. */
data class InstalledMapPack(
    val regionId: String,
    val version: String,
    val vectorFile: File,
    val demFile: File,
    val area: MapPackArea,
    val bytes: Long,
)

/**
 * The packs' files on the phone. [root] is the app's own folder on the phone's storage
 * (`Android/data/<app>/files/maps`): no permission needed, Android deletes it when the app is
 * uninstalled, and the app has backups off (`allowBackup="false"`), so packs never go into a backup.
 *
 * - `<region>/<version>/<files>`: the installed version; `<region>/installed.json` says which one.
 * - `<region>/download-<version>/`: a download in progress; it only becomes the installed version
 *   once every file's size and SHA-256 match the manifest ([install]).
 * - `download.json`: the download in progress ([PendingDownload]), so it carries on after the app
 *   process is gone.
 * - `manifest.json`: the last manifest read, so the screen can show sizes with no internet.
 */
class MapPackStore(val root: File) {

    fun installed(): List<InstalledMapPack> =
        root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { readInstalled(it) }

    private fun readInstalled(regionDir: File): InstalledMapPack? = runCatching {
        val json = Json.parseToJsonElement(File(regionDir, INSTALLED_JSON).readText()).jsonObject
        val version = json.getValue("version").jsonPrimitive.content
        val dir = File(regionDir, version)
        val pack = InstalledMapPack(
            regionId = regionDir.name,
            version = version,
            vectorFile = File(dir, json.getValue("vector").jsonPrimitive.content),
            demFile = File(dir, json.getValue("dem").jsonPrimitive.content),
            area = MapPackArea.fromJson(json.getValue("area").jsonArray),
            bytes = json.getValue("bytes").jsonPrimitive.long,
        )
        pack.takeIf { it.vectorFile.isFile && it.demFile.isFile }
    }.getOrNull()

    /** Where [region]'s files download to before they're checked. */
    fun downloadDir(regionId: String, version: String) = File(File(root, regionId), "download-$version")

    /**
     * Checks the downloaded files of [region] against the manifest, then makes them the installed
     * version and deletes every other version. Null (and the downloaded files deleted) when a file is
     * missing, the wrong size, or its SHA-256 doesn't match.
     */
    fun install(region: MapPackRegion): InstalledMapPack? {
        val download = downloadDir(region.id, region.version)
        val ok = region.isComplete && region.files.all { file ->
            val f = File(download, file.name)
            f.isFile && f.length() == file.bytes && sha256(f) == file.sha256
        }
        if (!ok) {
            download.deleteRecursively()
            return null
        }
        val regionDir = File(root, region.id)
        val target = File(regionDir, region.version)
        target.deleteRecursively()
        check(download.renameTo(target)) { "can't move ${download.name} into place" }
        val installed = buildJsonObject {
            put("version", region.version)
            put("vector", region.files.first { it.role == MapPackFile.ROLE_VECTOR }.name)
            put("dem", region.files.first { it.role == MapPackFile.ROLE_DEM }.name)
            put("bytes", region.totalBytes)
            put("area", region.area.toJson())
        }
        writeAtomically(File(regionDir, INSTALLED_JSON), installed.toString())
        regionDir.listFiles().orEmpty().filter { it.isDirectory && it.name != region.version }.forEach { it.deleteRecursively() }
        return readInstalled(regionDir)
    }

    /** Deletes [regionId]'s pack: installed files, any half-downloaded ones, and its record. */
    fun delete(regionId: String) {
        File(root, regionId).deleteRecursively()
    }

    fun readPendingDownload(): PendingDownload? = runCatching {
        val json = Json.parseToJsonElement(File(root, DOWNLOAD_JSON).readText()).jsonObject
        PendingDownload(
            regionId = json.getValue("region").jsonPrimitive.content,
            version = json.getValue("version").jsonPrimitive.content,
            downloadIds = json.getValue("ids").jsonArray.map { it.jsonPrimitive.long },
            manifest = CachedManifest(json.getValue("manifest").jsonPrimitive.content, json.getValue("url").jsonPrimitive.content),
        )
    }.getOrNull()

    fun writePendingDownload(pending: PendingDownload?) {
        val file = File(root, DOWNLOAD_JSON)
        if (pending == null) {
            file.delete()
            return
        }
        writeAtomically(
            file,
            buildJsonObject {
                put("region", pending.regionId)
                put("version", pending.version)
                putJsonArray("ids") { pending.downloadIds.forEach { add(JsonPrimitive(it)) } }
                put("manifest", pending.manifest.json)
                put("url", pending.manifest.url)
            }.toString(),
        )
    }

    /** The last manifest read, so the screen can show what there is to download with no internet. */
    fun readCachedManifest(): CachedManifest? = runCatching {
        val json = Json.parseToJsonElement(File(root, MANIFEST_JSON).readText()).jsonObject
        CachedManifest(json.getValue("manifest").jsonPrimitive.content, json.getValue("url").jsonPrimitive.content)
    }.getOrNull()

    fun writeCachedManifest(manifest: CachedManifest) {
        writeAtomically(File(root, MANIFEST_JSON), buildJsonObject { put("manifest", manifest.json); put("url", manifest.url) }.toString())
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        check(temp.renameTo(file)) { "can't write ${file.name}" }
    }

    companion object {
        private const val INSTALLED_JSON = "installed.json"
        private const val DOWNLOAD_JSON = "download.json"
        private const val MANIFEST_JSON = "manifest.json"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * A pack download handed to Android's download manager: one download id per file, and the manifest
 * it came from (the files' sizes and SHA-256 to check them against when they're done).
 */
data class PendingDownload(val regionId: String, val version: String, val downloadIds: List<Long>, val manifest: CachedManifest)

/** A manifest's text and the address it came from (its file names resolve against it). */
data class CachedManifest(val json: String, val url: String) {
    fun parse(): MapPackManifest? = MapPackManifest.parse(json, url)
}
