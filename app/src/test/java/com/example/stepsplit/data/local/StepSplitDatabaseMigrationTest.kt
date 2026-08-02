package com.example.stepsplit.data.local

import androidx.room.Room
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
 * [manualwalk.ManualWalkEntity.autoCompletionMessageShown], originally for the manual-walk
 * auto-completion feature) is both schema-correct and row-preserving. Still relevant after the
 * manual-walk feature itself was removed: `manual_walks`, its entity, and this migration are
 * deliberately kept (see [StepSplitDatabase]'s doc comment) so an existing installation's rows are
 * never destroyed by an upgrade, even though the app no longer reads them.
 *
 * Two things are verified together:
 *
 * 1. **Full schema validation.** The starting database is built from the *complete* version-1
 *    schema - all four tables, indices, and the `room_master_table` identity row - using the
 *    exact `createSql`/`setupQueries` Room itself generated into the exported
 *    `app/schemas/.../1.json`, not a hand-abridged approximation. It is then opened through a
 *    real [StepSplitDatabase] (via [Room.databaseBuilder], with [StepSplitDatabase.MIGRATIONS]
 *    registered exactly as production does in [StepSplitDatabase.build]), which forces Room's own
 *    internal open-time validation: after running the migration, Room introspects the actual
 *    on-disk schema of *every* table and compares it field-by-field, index-by-index against what
 *    its compiled v2 entities expect. If the migration left anything - a column, a type, a
 *    nullability flag, an index - not matching, Room throws here. This is the same mechanism
 *    `MigrationTestHelper` itself relies on internally.
 *
 *    `MigrationTestHelper` was not used directly to drive this: as of Room 2.8.4 it has a
 *    database-path resolution issue under Robolectric with `applicationIdSuffix` set (confirmed
 *    while first building this test), separate from the schema-asset-loading friction noted
 *    below. Building the starting schema by hand from the already-exported JSON and letting a
 *    real [StepSplitDatabase] open it gives the same validation guarantee without depending on
 *    that helper's own internals.
 *
 * 2. **Row preservation.** A finished and a still-ongoing `manual_walks` row both survive the
 *    upgrade unchanged, with the two new columns defaulting to `0`/false.
 */
@RunWith(RobolectricTestRunner::class)
class StepSplitDatabaseMigrationTest {

    // Kept short deliberately: Robolectric embeds the full test method name into its temp
    // directory path, and a long, descriptive backtick name can push the total path past
    // Windows' MAX_PATH, which surfaces as a generic, misleading SQLiteCantOpenDatabaseException.
    @Test
    fun `v1 to v2 full schema migration`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "schema.db"
        context.deleteDatabase(dbName)

        try {
            buildCompleteV1Database(context, dbName)

            // The real production database, with the real production migration registered -
            // exactly how StepSplitDatabase.build(context) constructs it. Accessing the
            // underlying SupportSQLiteOpenHelper forces Room to actually open (and therefore
            // migrate + validate) the database now, rather than lazily on first DAO query.
            val roomDb = Room.databaseBuilder(context, StepSplitDatabase::class.java, dbName)
                .addMigrations(*StepSplitDatabase.MIGRATIONS)
                .build()
            try {
                val migrated = roomDb.openHelper.writableDatabase

                val finishedCursor = migrated.query(
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

                val ongoingCursor = migrated.query(
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
                roomDb.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /**
     * Builds the exact version-1 schema - all four tables, their indices, and the
     * `room_master_table` identity row - from the same `createSql`/`setupQueries` Room exported to
     * `app/schemas/com.example.stepsplit.data.local.StepSplitDatabase/1.json`, then seeds two
     * `manual_walks` rows (one finished, one still ongoing). A plain [SupportSQLiteOpenHelper]
     * (not Room) does the building, closed again before Room itself opens the same file below.
     */
    private fun buildCompleteV1Database(context: android.content.Context, dbName: String) {
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `step_buckets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`source` TEXT NOT NULL, `startEpochSecond` INTEGER NOT NULL, `endEpochSecond` INTEGER NOT NULL, " +
                                "`steps` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `localDate` TEXT NOT NULL, " +
                                "`importedAtEpochSecond` INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_step_buckets_source_startEpochSecond` " +
                                "ON `step_buckets` (`source`, `startEpochSecond`)",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `walk_bouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`startEpochSecond` INTEGER NOT NULL, `endEpochSecond` INTEGER NOT NULL, `steps` INTEGER NOT NULL, " +
                                "`activeMinutes` INTEGER NOT NULL, `elapsedMinutes` INTEGER NOT NULL, `cadence` REAL NOT NULL, " +
                                "`autoClassification` TEXT NOT NULL, `autoConfidence` REAL NOT NULL, `autoReasonCode` TEXT NOT NULL, " +
                                "`classifierVersion` INTEGER NOT NULL, `computedAtEpochSecond` INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_walk_bouts_startEpochSecond` " +
                                "ON `walk_bouts` (`startEpochSecond`)",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `session_overrides` (`boutStartEpochSecond` INTEGER NOT NULL, " +
                                "`classification` TEXT NOT NULL, `overriddenAtEpochSecond` INTEGER NOT NULL, " +
                                "PRIMARY KEY(`boutStartEpochSecond`))",
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `manual_walks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`startEpochSecond` INTEGER NOT NULL, `endEpochSecond` INTEGER, `steps` INTEGER, " +
                                "`createdAtEpochSecond` INTEGER NOT NULL)",
                        )
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) " +
                                "VALUES(42, '647a7bff7aa146a476b5b6aa84e7c50c')",
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
        } finally {
            openHelper.close()
        }
    }
}
