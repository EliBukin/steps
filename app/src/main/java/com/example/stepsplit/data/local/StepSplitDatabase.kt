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
import com.example.stepsplit.data.local.manualwalk.ManualWalkDao
import com.example.stepsplit.data.local.manualwalk.ManualWalkEntity
import com.example.stepsplit.data.local.override.SessionOverrideDao
import com.example.stepsplit.data.local.override.SessionOverrideEntity

/**
 * Deliberately no `fallbackToDestructiveMigration()`: this is a single-user local health/fitness
 * database, and a schema upgrade must never silently wipe a user's step history. Future schema
 * changes must ship an explicit [androidx.room.migration.Migration] added to [MIGRATIONS] below.
 */
@Database(
    entities = [
        StepBucketEntity::class,
        WalkBoutEntity::class,
        SessionOverrideEntity::class,
        ManualWalkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class StepSplitDatabase : RoomDatabase() {
    abstract fun stepBucketDao(): StepBucketDao
    abstract fun walkBoutDao(): WalkBoutDao
    abstract fun sessionOverrideDao(): SessionOverrideDao
    abstract fun manualWalkDao(): ManualWalkDao

    companion object {
        private const val DATABASE_NAME = "stepsplit.db"

        /**
         * v1 -> v2: adds [ManualWalkEntity.autoCompleted] and
         * [ManualWalkEntity.autoCompletionMessageShown] for the manual-walk inactivity
         * auto-completion feature. Both are plain additive columns with SQLite-level defaults, so
         * every existing row (finished or ongoing) is preserved exactly as-is; no data is
         * rewritten or dropped.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manual_walks ADD COLUMN autoCompleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE manual_walks ADD COLUMN autoCompletionMessageShown INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Internal (not private) so migration tests can run these exact objects directly against real schema JSON via MigrationTestHelper. */
        internal val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)

        fun build(context: Context): StepSplitDatabase =
            Room.databaseBuilder(context.applicationContext, StepSplitDatabase::class.java, DATABASE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
