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
        ProgressPhotoEntity::class,
    ],
    version = 4,
    exportSchema = true,
    // Each step only adds a table (water at 2, fasts at 3, photos at 4), which Room can
    // migrate on its own. Anyone upgrading keeps every reading they already had; a
    // destructive fallback here would wipe years of history, the worst thing this app could do.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class WeightTrackDatabase : RoomDatabase() {
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun goalDao(): GoalDao
    abstract fun waterDao(): WaterDao
    abstract fun fastDao(): FastDao
    abstract fun progressPhotoDao(): ProgressPhotoDao

    companion object {
        const val NAME = "weighttrack.db"
    }
}
