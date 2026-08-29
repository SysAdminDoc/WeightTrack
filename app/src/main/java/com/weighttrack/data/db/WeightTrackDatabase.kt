package com.weighttrack.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WeightEntryEntity::class,
        MeasurementEntity::class,
        GoalEntity::class,
        WaterEntryEntity::class,
        FastEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // Each step only adds a table (water at 2, fasts at 3), which Room can migrate on its
    // own. Anyone upgrading keeps every reading they already had; a destructive fallback here
    // would silently wipe years of history, which is the worst thing this app could do.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class WeightTrackDatabase : RoomDatabase() {
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun goalDao(): GoalDao
    abstract fun waterDao(): WaterDao
    abstract fun fastDao(): FastDao

    companion object {
        const val NAME = "weighttrack.db"
    }
}
