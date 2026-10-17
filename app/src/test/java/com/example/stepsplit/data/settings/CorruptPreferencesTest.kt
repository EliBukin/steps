package com.example.stepsplit.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A preferences file that can't be parsed, as a torn write or a bad flash block leaves it. Plain
 * DataStores over such a file, since the app's own two are process-wide singletons: one with no
 * handler, as the app had, and one with the app's [resetCorruptPreferences], which both of its
 * stores now use.
 */
class CorruptPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val goal = longPreferencesKey("daily_goal_steps")

    @After
    fun tearDown() = scope.cancel()

    private fun corruptFile(): File =
        folder.newFile("stepsplit_settings.preferences_pb").apply { writeBytes(byteArrayOf(-1, -1, -1, -1) + "garbage".toByteArray()) }

    @Test
    fun `without a handler every read of a corrupt file fails - the app crashed on launch`() {
        val file = corruptFile()
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }

        assertThrows(CorruptionException::class.java) { runBlocking { store.data.first() } }
    }

    @Test
    fun `the app's handler reads a corrupt file as defaults and keeps it writable`() = runBlocking {
        val file = corruptFile()
        val store = PreferenceDataStoreFactory.create(corruptionHandler = resetCorruptPreferences, scope = scope) { file }

        assertEquals(emptyPreferences(), store.data.first())

        store.edit { it[goal] = 20_000L }
        assertEquals(20_000L, store.data.first()[goal])
    }
}
