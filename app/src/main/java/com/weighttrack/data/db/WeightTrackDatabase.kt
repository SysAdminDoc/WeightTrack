package com.weighttrack.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WeightEntryEntity::class,
        MeasurementEntity::class,
        GoalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class WeightTrackDatabase : RoomDatabase() {
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun goalDao(): GoalDao

    companion object {
        const val NAME = "weighttrack.db"
    }
}
