package com.example.stepsplit.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.stepsplit.data.local.bout.WalkBoutDao
import com.example.stepsplit.data.local.bout.WalkBoutEntity
import com.example.stepsplit.data.local.bucket.StepBucketDao
import com.example.stepsplit.data.local.bucket.StepBucketEntity
import com.example.stepsplit.data.local.manualwalk.ManualWalkEntity
import com.example.stepsplit.data.local.motion.ActivityIntervalDao
import com.example.stepsplit.data.local.motion.ActivityIntervalEntity
import com.example.stepsplit.data.local.motion.MotionEvidenceDao
import com.example.stepsplit.data.local.motion.MotionEvidenceEntity
import com.example.stepsplit.data.local.motion.TemporalContinuityStateDao
import com.example.stepsplit.data.local.motion.TemporalContinuityStateEntity
import com.example.stepsplit.data.local.override.SessionOverrideDao
import com.example.stepsplit.data.local.override.SessionOverrideEntity
import com.example.stepsplit.data.local.trip.TripDao
import com.example.stepsplit.data.local.trip.TripEntity
import com.example.stepsplit.data.local.trip.TripPointDao
import com.example.stepsplit.data.local.trip.TripPointEntity

/**
 * Deliberately no `fallbackToDestructiveMigration()`: this is a single-user local health/fitness
 * database, and a schema upgrade must never silently wipe a user's step history. Future schema
 * changes must ship an explicit [androidx.room.migration.Migration] added to [MIGRATIONS] below.
 *
 * [ManualWalkEntity] backs the removed manual "Start walk / Finish walk" feature and is no longer
 * read or written anywhere in product code (see the entity's own doc comment). It is kept in
 * [entities] - and [MIGRATION_1_2] is kept as-is - purely for compatibility: an existing
 * installation may already be on schema version 2 with real `manual_walks` rows, and removing an
 * entity/table safely requires its own dedicated migration (`DROP TABLE`) rather than an
 * opportunistic deletion alongside an unrelated change. Dropping `manual_walks` is left to a
 * future, dedicated migration that bumps [version] to 3. There is deliberately no DAO for it
 * anymore - only the entity declaration is needed to keep the schema consistent.
 */
@Database(
    entities = [
        StepBucketEntity::class,
        WalkBoutEntity::class,
        SessionOverrideEntity::class,
        ManualWalkEntity::class,
        TripEntity::class,
        TripPointEntity::class,
        ActivityIntervalEntity::class,
        TemporalContinuityStateEntity::class,
        MotionEvidenceEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class StepSplitDatabase : RoomDatabase() {
    abstract fun stepBucketDao(): StepBucketDao
    abstract fun walkBoutDao(): WalkBoutDao
    abstract fun sessionOverrideDao(): SessionOverrideDao
    abstract fun tripDao(): TripDao
    abstract fun tripPointDao(): TripPointDao
    abstract fun activityIntervalDao(): ActivityIntervalDao
    abstract fun temporalContinuityStateDao(): TemporalContinuityStateDao
    abstract fun motionEvidenceDao(): MotionEvidenceDao

    companion object {
        private const val DATABASE_NAME = "stepsplit.db"

        /**
         * v1 -> v2: adds [ManualWalkEntity.autoCompleted] and
         * [ManualWalkEntity.autoCompletionMessageShown], originally for the manual-walk inactivity
         * auto-completion feature (since removed - see [ManualWalkEntity]). Both are plain
         * additive columns with SQLite-level defaults, so every existing row (finished or ongoing)
         * is preserved exactly as-is; no data is rewritten or dropped. Kept unchanged for
         * migration compatibility even though nothing reads these columns anymore.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manual_walks ADD COLUMN autoCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE manual_walks ADD COLUMN autoCompletionMessageShown INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 -> v3: adds the Trip Route Recording tables (`trips`, `trip_points`) - purely
         * additive, touches no existing table, so every existing `step_buckets`, `walk_bouts`,
         * `session_overrides`, and `manual_walks` row (including deprecated ones) is preserved
         * exactly as-is. `trip_points.tripId` cascades on delete of its parent `trips` row.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trips` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startEpochSecond` INTEGER NOT NULL, " +
                        "`endEpochSecond` INTEGER, " +
                        "`startZoneId` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, " +
                        "`lastAcceptedPointEpochSecond` INTEGER, " +
                        "`createdAtEpochSecond` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trip_points` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tripId` INTEGER NOT NULL, " +
                        "`capturedAtEpochSecond` INTEGER NOT NULL, " +
                        "`latitude` REAL NOT NULL, " +
                        "`longitude` REAL NOT NULL, " +
                        "`accuracyMeters` REAL NOT NULL, " +
                        "`altitudeMeters` REAL, " +
                        "`speedMetersPerSecond` REAL, " +
                        "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trip_points_tripId_capturedAtEpochSecond` " +
                        "ON `trip_points` (`tripId`, `capturedAtEpochSecond`)",
                )
            }
        }

        /**
         * v3 -> v4: strict vehicle-aware step validation. Purely additive - every existing
         * `step_buckets` row survives untouched apart from the new columns, which SQLite's
         * `ALTER TABLE ... ADD COLUMN ... DEFAULT` applies to every existing row at ALTER time
         * (the same mechanic [MIGRATION_1_2] already relies on for `autoCompleted`). Every
         * pre-existing row becomes `validationState = 'LEGACY_UNVERIFIED'` - it predates motion
         * evidence collection and cannot honestly be called vehicle-verified, but its raw [steps]
         * value is preserved exactly, permanently, never deleted or reset (see
         * [com.example.stepsplit.data.local.bucket.StepBucketDao.observeLegacyAggregate]). No
         * existing table (`walk_bouts`, `session_overrides`, `trips`, `trip_points`, `manual_walks`)
         * is touched at all.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN validationState TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'")
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN acceptedSteps INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN rejectionReason TEXT")
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN policyVersion INTEGER")
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN validatedAtEpochSecond INTEGER")
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN observationStartEpochSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE step_buckets ADD COLUMN observationEndEpochSecond INTEGER NOT NULL DEFAULT 0")
                // ALTER...DEFAULT cannot express "copy from another column" - backfill the
                // observation span from each row's own existing minute bounds explicitly.
                db.execSQL(
                    "UPDATE step_buckets SET observationStartEpochSecond = startEpochSecond, " +
                        "observationEndEpochSecond = endEpochSecond",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_step_buckets_source_validationState` " +
                        "ON `step_buckets` (`source`, `validationState`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activity_intervals` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`activityType` TEXT NOT NULL, " +
                        "`startWallClockEpochMilli` INTEGER NOT NULL, " +
                        "`endWallClockEpochMilli` INTEGER, " +
                        "`temporalContinuityEpoch` INTEGER NOT NULL, " +
                        "`closedReason` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activity_intervals_activityType_endWallClockEpochMilli` " +
                        "ON `activity_intervals` (`activityType`, `endWallClockEpochMilli`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activity_intervals_activityType_startWallClockEpochMilli` " +
                        "ON `activity_intervals` (`activityType`, `startWallClockEpochMilli`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `temporal_continuity_state` (" +
                        "`id` INTEGER PRIMARY KEY NOT NULL, " +
                        "`bootSessionId` INTEGER NOT NULL, " +
                        "`bootEpochOffsetMillis` INTEGER NOT NULL, " +
                        "`temporalContinuityEpoch` INTEGER NOT NULL)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `motion_evidence` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`activityType` TEXT NOT NULL, " +
                        "`confidence` INTEGER, " +
                        "`eventElapsedRealtimeMillis` INTEGER NOT NULL, " +
                        "`bootSessionId` INTEGER NOT NULL, " +
                        "`derivedWallClockEpochMilli` INTEGER NOT NULL, " +
                        "`temporalContinuityEpoch` INTEGER NOT NULL, " +
                        "`receivedAtEpochMilli` INTEGER NOT NULL, " +
                        "`dedupeKey` TEXT NOT NULL, " +
                        "`batchId` TEXT)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_motion_evidence_dedupeKey` ON `motion_evidence` (`dedupeKey`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_motion_evidence_derivedWallClockEpochMilli` " +
                        "ON `motion_evidence` (`derivedWallClockEpochMilli`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_motion_evidence_bootSessionId` ON `motion_evidence` (`bootSessionId`)")
            }
        }

        /** Internal (not private) so migration tests can run these exact objects directly against real schema JSON via MigrationTestHelper. */
        internal val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun build(context: Context): StepSplitDatabase =
            Room.databaseBuilder(context.applicationContext, StepSplitDatabase::class.java, DATABASE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
