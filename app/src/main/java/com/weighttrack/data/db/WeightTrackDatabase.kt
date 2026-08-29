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
    ],
    version = 2,
    exportSchema = true,
    // Version 2 only adds the water table, which Room can migrate on its own. Anyone
    // upgrading keeps every reading they already had; a destructive fallback here would
    // silently wipe years of history, which is the single worst thing this app could do.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class WeightTrackDatabase : RoomDatabase() {
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun goalDao(): GoalDao
    abstract fun waterDao(): WaterDao

    companion object {
        const val NAME = "weighttrack.db"
    }
}
