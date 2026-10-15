package com.example.stepsplit.ui.trips

import android.content.Context
import android.content.pm.ProviderInfo
import androidx.core.content.FileProvider
import org.robolectric.Robolectric

/**
 * FileProvider keeps each authority's folders in a static cache, while Robolectric gives every test
 * fresh app folders. Without clearing it, a test resolves files against the folders of whichever
 * earlier test used the provider first, and fails with "Failed to find configured root".
 */
internal fun clearFileProviderCache() {
    val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null)
    (cache as MutableMap<*, *>).clear()
}

/**
 * Registers this app's FileProvider so a ContentResolver can open its `content://` Uris, as on a
 * device. Declared like the manifest's entry: FileProvider refuses to start without the grant flag.
 */
internal fun registerFileProvider(context: Context) {
    val info = ProviderInfo().apply {
        authority = GpxShare.authority(context)
        grantUriPermissions = true
        exported = false
    }
    Robolectric.buildContentProvider(FileProvider::class.java).create(info)
}
