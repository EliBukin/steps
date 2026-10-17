package com.example.stepsplit.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole migration chain at once: a database created at version 1 (from the exported `1.json`)
 * and taken through every migration to the current version must end up with the same tables,
 * columns, types, nullability, primary keys, foreign keys and indices as a fresh install. The
 * per-step tests in [StepSplitDatabaseMigrationTest] each check one step; this checks they add up.
 *
 * One difference is expected and allowed: columns added by `ALTER TABLE ... ADD COLUMN ... DEFAULT`
 * keep that SQL default on an upgraded database, where a fresh one has none. Room writes every
 * column on every insert, so the default is never used - and these columns all belong to removed
 * features and are never written at all (see [StepSplitDatabase]).
 */
@RunWith(RobolectricTestRunner::class)
class FreshVersusUpgradedSchemaTest {

    /** Columns whose SQL default only an upgraded database has - see the class doc comment. */
    private val upgradeOnlyDefaults = setOf(
        "manual_walks.autoCompleted",
        "manual_walks.autoCompletionMessageShown",
        "step_buckets.validationState",
        "step_buckets.acceptedSteps",
        "step_buckets.observationStartEpochSecond",
        "step_buckets.observationEndEpochSecond",
    )

    @Test
    fun `v1 migrated to the latest version matches a fresh install`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(UPGRADED)
        context.deleteDatabase(FRESH)
        createVersion1(context)

        val upgraded = Room.databaseBuilder(context, StepSplitDatabase::class.java, UPGRADED).addMigrations(*StepSplitDatabase.MIGRATIONS).build()
        val fresh = Room.databaseBuilder(context, StepSplitDatabase::class.java, FRESH).addMigrations(*StepSplitDatabase.MIGRATIONS).build()
        try {
            val upgradedSchema = describe(upgraded.openHelper.writableDatabase, ignoreDefaults = upgradeOnlyDefaults)
            val freshSchema = describe(fresh.openHelper.writableDatabase, ignoreDefaults = upgradeOnlyDefaults)

            assertEquals(freshSchema.keys, upgradedSchema.keys)
            for (key in freshSchema.keys) assertEquals(key, freshSchema[key], upgradedSchema[key])
        } finally {
            upgraded.close()
            fresh.close()
            context.deleteDatabase(UPGRADED)
            context.deleteDatabase(FRESH)
        }
    }

    /** Every table's columns, foreign keys and indices, as SQLite itself reports them. */
    private fun describe(db: SupportSQLiteDatabase, ignoreDefaults: Set<String>): Map<String, String> {
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata', 'room_master_table')").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        val schema = sortedMapOf<String, String>()
        for (table in tables) {
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1)
                    val default = if ("$table.$name" in ignoreDefaults) "-" else c.getString(4)
                    columns += "$name ${c.getString(2)} notNull=${c.getInt(3)} default=$default pk=${c.getInt(5)}"
                }
            }
            schema["$table columns"] = columns.sorted().joinToString("; ")

            val foreignKeys = mutableListOf<String>()
            db.query("PRAGMA foreign_key_list(`$table`)").use { c ->
                while (c.moveToNext()) foreignKeys += "${c.getString(3)} -> ${c.getString(2)}.${c.getString(4)} onUpdate=${c.getString(5)} onDelete=${c.getString(6)}"
            }
            schema["$table foreign keys"] = foreignKeys.sorted().joinToString("; ")

            val indices = mutableListOf<String>()
            db.query("PRAGMA index_list(`$table`)").use { c ->
                while (c.moveToNext()) {
                    val index = c.getString(1)
                    if (index.startsWith("sqlite_autoindex")) continue
                    val indexColumns = mutableListOf<String>()
                    db.query("PRAGMA index_info(`$index`)").use { ic -> while (ic.moveToNext()) indexColumns += ic.getString(2) }
                    indices += "$index unique=${c.getInt(2)} (${indexColumns.joinToString()})"
                }
            }
            schema["$table indices"] = indices.sorted().joinToString("; ")
        }
        return schema
    }

    /** The version-1 schema, exactly as Room exported it to `1.json`. */
    private fun createVersion1(context: android.content.Context) {
        val database = JSONObject(File("schemas/com.example.stepsplit.data.local.StepSplitDatabase/1.json").readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val setupQueries = database.getJSONArray("setupQueries")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(UPGRADED)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                        }
                        for (i in 0 until setupQueries.length()) db.execSQL(setupQueries.getString(i))
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }

    private companion object {
        const val UPGRADED = "upgraded.db"
        const val FRESH = "fresh.db"
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
