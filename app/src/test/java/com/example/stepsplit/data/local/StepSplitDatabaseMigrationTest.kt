package com.example.stepsplit.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves the v1 -> v2 migration (adding [manualwalk.ManualWalkEntity.autoCompleted] and
 * [manualwalk.ManualWalkEntity.autoCompletionMessageShown] for the auto-completion feature) is
 * purely additive: every pre-existing row - finished or still ongoing - survives the upgrade
 * unchanged, with the two new columns defaulting to false.
 *
 * This runs [StepSplitDatabase.MIGRATIONS] - the real production migration object - directly
 * against a real SQLite database built with the version-1 `manual_walks` schema, via the same
 * [SupportSQLiteDatabase] type Room itself passes to a [androidx.room.migration.Migration]. A
 * plain [SupportSQLiteOpenHelper] is used instead of Room's `MigrationTestHelper` (which needs
 * exported schema JSON bundled as an app asset and, as of Room 2.8.4, has a database-path
 * resolution issue under Robolectric with `applicationIdSuffix` set) - this still exercises the
 * exact migration code that ships, just without Room's own schema-consistency validation layer.
 */
@RunWith(RobolectricTestRunner::class)
class StepSplitDatabaseMigrationTest {

    // Kept short deliberately: Robolectric embeds the full test method name into its temp
    // directory path, and a long, descriptive backtick name can push the total path past
    // Windows' MAX_PATH, which surfaces as a generic, misleading SQLiteCantOpenDatabaseException.
    @Test
    fun `v1 to v2 migration preserves rows`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "migtest.db"
        context.deleteDatabase(dbName)

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The exact version-1 manual_walks schema, before autoCompleted/autoCompletionMessageShown existed.
                        db.execSQL(
                            "CREATE TABLE manual_walks (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "startEpochSecond INTEGER NOT NULL, " +
                                "endEpochSecond INTEGER, " +
                                "steps INTEGER, " +
                                "createdAtEpochSecond INTEGER NOT NULL)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )

        try {
            val db = openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO manual_walks (id, startEpochSecond, endEpochSecond, steps, createdAtEpochSecond) " +
                    "VALUES (1, 1000, 1600, 42, 1000)",
            )
            db.execSQL(
                "INSERT INTO manual_walks (id, startEpochSecond, endEpochSecond, steps, createdAtEpochSecond) " +
                    "VALUES (2, 5000, NULL, NULL, 5000)",
            )

            // The real production migration, run against a real database.
            StepSplitDatabase.MIGRATIONS.single().migrate(db)

            val finishedCursor = db.query(
                "SELECT startEpochSecond, endEpochSecond, steps, autoCompleted, autoCompletionMessageShown " +
                    "FROM manual_walks WHERE id = 1",
            )
            assertTrue(finishedCursor.moveToFirst())
            assertEquals(1000L, finishedCursor.getLong(0))
            assertEquals(1600L, finishedCursor.getLong(1))
            assertEquals(42L, finishedCursor.getLong(2))
            assertEquals(0, finishedCursor.getInt(3))
            assertEquals(0, finishedCursor.getInt(4))
            finishedCursor.close()

            val ongoingCursor = db.query(
                "SELECT startEpochSecond, endEpochSecond, steps, autoCompleted, autoCompletionMessageShown " +
                    "FROM manual_walks WHERE id = 2",
            )
            assertTrue(ongoingCursor.moveToFirst())
            assertEquals(5000L, ongoingCursor.getLong(0))
            assertTrue(ongoingCursor.isNull(1))
            assertTrue(ongoingCursor.isNull(2))
            assertEquals(0, ongoingCursor.getInt(3))
            assertEquals(0, ongoingCursor.getInt(4))
            ongoingCursor.close()
        } finally {
            openHelper.close()
            context.deleteDatabase(dbName)
        }
    }
}
