package com.example.stepsplit.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true,
)
abstract class StepSplitDatabase : RoomDatabase() {
    abstract fun stepBucketDao(): StepBucketDao
    abstract fun walkBoutDao(): WalkBoutDao
    abstract fun sessionOverrideDao(): SessionOverrideDao
    abstract fun manualWalkDao(): ManualWalkDao

    companion object {
        private const val DATABASE_NAME = "stepsplit.db"

        /** No migrations yet - version 1 is the first shipped schema. */
        private val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()

        fun build(context: Context): StepSplitDatabase =
            Room.databaseBuilder(context.applicationContext, StepSplitDatabase::class.java, DATABASE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
