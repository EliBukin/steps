package com.example.stepsplit.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONObject
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
 * 1. **Full schema validation.** The starting database is built by genuinely parsing the exported
 *    `app/schemas/com.example.stepsplit.data.local.StepSplitDatabase/1.json` at test time (see
 *    [buildCompleteV1Database]) and executing the exact `createSql`/`setupQueries` strings Room
 *    itself generated into that file - not a hand-copied approximation that could silently drift
 *    from it. The resulting database is then opened through a real [StepSplitDatabase] (via
 *    [Room.databaseBuilder], with [StepSplitDatabase.MIGRATIONS] registered exactly as production
 *    does in [StepSplitDatabase.build]), which forces Room's own internal open-time validation:
 *    after running the migration, Room introspects the actual on-disk schema of *every* table and
 *    compares it field-by-field, index-by-index against what its compiled v2 entities expect. If
 *    the migration left anything - a column, a type, a nullability flag, an index - not matching,
 *    Room throws here. This is the same mechanism `MigrationTestHelper` itself relies on
 *    internally.
 *
 *    `MigrationTestHelper` was not used directly to drive this: as of Room 2.8.4 it has a
 *    database-path resolution issue under Robolectric with `applicationIdSuffix` set (confirmed
 *    while first building this test). Parsing the already-exported JSON directly and letting a
 *    real [StepSplitDatabase] open the result gives the same validation guarantee without
 *    depending on that helper's own internals.
 *
 *    Parsing the schema file this way relies on the JVM working directory being the `app/` module
 *    root, which is how Gradle actually runs `testDebugUnitTest` (confirmed empirically) - not an
 *    assumption specific to any one IDE test runner.
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
     * `room_master_table` identity row - by parsing `1.json` itself (see the class-level doc
     * comment) and executing its `createSql`/`setupQueries` strings verbatim, then seeds two
     * `manual_walks` rows (one finished, one still ongoing). A plain [SupportSQLiteOpenHelper]
     * (not Room) does the building, closed again before Room itself opens the same file below.
     */
    private fun buildCompleteV1Database(context: android.content.Context, dbName: String) {
        val schemaFile = File("schemas/com.example.stepsplit.data.local.StepSplitDatabase/1.json")
        check(schemaFile.exists()) {
            "Expected the exported v1 schema at ${schemaFile.absolutePath} - " +
                "this test must run with the app/ module directory as the working directory " +
                "(true for `gradlew testDebugUnitTest`, which this project's tests are run under)."
        }
        val database = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val setupQueries = database.getJSONArray("setupQueries")

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val tableName = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace(TABLE_NAME_PLACEHOLDER, tableName))

                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                val indexSql = indices.getJSONObject(j).getString("createSql")
                                db.execSQL(indexSql.replace(TABLE_NAME_PLACEHOLDER, tableName))
                            }
                        }
                        for (i in 0 until setupQueries.length()) {
                            db.execSQL(setupQueries.getString(i))
                        }
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

    /**
     * Proves the v2 -> v3 migration (adding the Trip Route Recording tables `trips` and
     * `trip_points` - see [StepSplitDatabase.MIGRATION_2_3]'s own doc comment) is both
     * schema-correct and preserves every existing table's data untouched, using the same
     * two-part strategy as [`v1 to v2 full schema migration`][buildCompleteV1Database] above: a
     * complete v2 database is built from the exact `createSql`/`setupQueries` in the exported
     * `2.json`, one representative row is seeded into all four v2 tables (`step_buckets`,
     * `walk_bouts`, `session_overrides`, and the deprecated-but-preserved `manual_walks`), and a
     * real [StepSplitDatabase] then migrates and validates it. Migrating straight from v2 (not v1)
     * additionally proves the whole registered [StepSplitDatabase.MIGRATIONS] chain works when
     * only its later segment actually needs to run.
     */
    @Test
    fun `v2 to v3 full schema migration`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dbName = "schema_v2_v3.db"
        context.deleteDatabase(dbName)

        try {
            buildCompleteV2Database(context, dbName)

            val roomDb = Room.databaseBuilder(context, StepSplitDatabase::class.java, dbName)
                .addMigrations(*StepSplitDatabase.MIGRATIONS)
                .build()
            try {
                val migrated = roomDb.openHelper.writableDatabase

                val bucketCursor = migrated.query(
                    "SELECT source, startEpochSecond, endEpochSecond, steps, zoneId, localDate, importedAtEpochSecond " +
                        "FROM step_buckets WHERE id = 1",
                )
                assertTrue(bucketCursor.moveToFirst())
                assertEquals("local_recording_api", bucketCursor.getString(0))
                assertEquals(1_000L, bucketCursor.getLong(1))
                assertEquals(1_060L, bucketCursor.getLong(2))
                assertEquals(42L, bucketCursor.getLong(3))
                bucketCursor.close()

                val boutCursor = migrated.query(
                    "SELECT startEpochSecond, endEpochSecond, steps, autoClassification, classifierVersion " +
                        "FROM walk_bouts WHERE id = 1",
                )
                assertTrue(boutCursor.moveToFirst())
                assertEquals(1_000L, boutCursor.getLong(0))
                assertEquals("WORKOUT", boutCursor.getString(3))
                boutCursor.close()

                val overrideCursor = migrated.query(
                    "SELECT classification, overriddenAtEpochSecond FROM session_overrides WHERE boutStartEpochSecond = 1000",
                )
                assertTrue(overrideCursor.moveToFirst())
                assertEquals("INCIDENTAL", overrideCursor.getString(0))
                overrideCursor.close()

                val manualWalkCursor = migrated.query(
                    "SELECT startEpochSecond, endEpochSecond, steps, autoCompleted, autoCompletionMessageShown " +
                        "FROM manual_walks WHERE id = 1",
                )
                assertTrue(manualWalkCursor.moveToFirst())
                assertEquals(1000L, manualWalkCursor.getLong(0))
                assertEquals(1600L, manualWalkCursor.getLong(1))
                manualWalkCursor.close()

                // New tables exist and are actually usable, not merely present.
                val emptyTripsCursor = migrated.query("SELECT COUNT(*) FROM trips")
                assertTrue(emptyTripsCursor.moveToFirst())
                assertEquals(0, emptyTripsCursor.getInt(0))
                emptyTripsCursor.close()

                migrated.execSQL(
                    "INSERT INTO trips (id, startEpochSecond, endEpochSecond, startZoneId, state, distanceMeters, " +
                        "lastAcceptedPointEpochSecond, createdAtEpochSecond) " +
                        "VALUES (1, 2000, NULL, 'UTC', 'ACTIVE', 0.0, NULL, 2000)",
                )
                migrated.execSQL(
                    "INSERT INTO trip_points (tripId, capturedAtEpochSecond, latitude, longitude, accuracyMeters, " +
                        "altitudeMeters, speedMetersPerSecond) VALUES (1, 2001, 32.0, 34.0, 10.0, NULL, NULL)",
                )
                val pointCursor = migrated.query("SELECT COUNT(*) FROM trip_points WHERE tripId = 1")
                assertTrue(pointCursor.moveToFirst())
                assertEquals(1, pointCursor.getInt(0))
                pointCursor.close()
            } finally {
                roomDb.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    /** Same approach as [buildCompleteV1Database], parsing `2.json` and seeding one row per v2 table. */
    private fun buildCompleteV2Database(context: android.content.Context, dbName: String) {
        val schemaFile = File("schemas/com.example.stepsplit.data.local.StepSplitDatabase/2.json")
        check(schemaFile.exists()) {
            "Expected the exported v2 schema at ${schemaFile.absolutePath} - " +
                "this test must run with the app/ module directory as the working directory " +
                "(true for `gradlew testDebugUnitTest`, which this project's tests are run under)."
        }
        val database = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val setupQueries = database.getJSONArray("setupQueries")

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val tableName = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace(TABLE_NAME_PLACEHOLDER, tableName))

                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                val indexSql = indices.getJSONObject(j).getString("createSql")
                                db.execSQL(indexSql.replace(TABLE_NAME_PLACEHOLDER, tableName))
                            }
                        }
                        for (i in 0 until setupQueries.length()) {
                            db.execSQL(setupQueries.getString(i))
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )

        try {
            val db = openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO step_buckets (id, source, startEpochSecond, endEpochSecond, steps, zoneId, localDate, importedAtEpochSecond) " +
                    "VALUES (1, 'local_recording_api', 1000, 1060, 42, 'UTC', '1970-01-01', 1060)",
            )
            db.execSQL(
                "INSERT INTO walk_bouts (id, startEpochSecond, endEpochSecond, steps, activeMinutes, elapsedMinutes, " +
                    "cadence, autoClassification, autoConfidence, autoReasonCode, classifierVersion, computedAtEpochSecond) " +
                    "VALUES (1, 1000, 2000, 500, 15, 16, 90.0, 'WORKOUT', 0.9, 'MEETS_ALL_THRESHOLDS', 1, 2000)",
            )
            db.execSQL(
                "INSERT INTO session_overrides (boutStartEpochSecond, classification, overriddenAtEpochSecond) " +
                    "VALUES (1000, 'INCIDENTAL', 2500)",
            )
            db.execSQL(
                "INSERT INTO manual_walks (id, startEpochSecond, endEpochSecond, steps, createdAtEpochSecond, autoCompleted, autoCompletionMessageShown) " +
                    "VALUES (1, 1000, 1600, 42, 1000, 0, 0)",
            )
        } finally {
            openHelper.close()
        }
    }

    private companion object {
        /** The placeholder Room itself writes into every `createSql` string in the exported schema JSON. */
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
