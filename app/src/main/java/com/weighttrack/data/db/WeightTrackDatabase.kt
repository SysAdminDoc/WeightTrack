package com.weighttrack.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WeightEntryEntity::class,
        MeasurementEntity::class,
        GoalEntity::class,
        WaterEntryEntity::class,
        FastEntity::class,
        ProgressPhotoEntity::class,
        ProfileEntity::class,
        FoodEntity::class,
        RecipeEntity::class,
        RecipeItemEntity::class,
    ],
    version = 6,
    exportSchema = true,
    // Each step up to 4 only adds a table (water at 2, fasts at 3, photos at 4). Step 5 adds
    // the profiles table and a profile column to everything that belongs to one, defaulting to
    // profile 1, which the spec below creates. Anyone upgrading keeps every reading they
    // already had; a destructive fallback here would wipe years of history, the worst thing
    // this app could do.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = WeightTrackDatabase.AddProfiles::class),
        // Six adds the food tables, which nothing else points at, so Room handles it alone.
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class WeightTrackDatabase : RoomDatabase() {
    abstract fun weightEntryDao(): WeightEntryDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun goalDao(): GoalDao
    abstract fun waterDao(): WaterDao
    abstract fun fastDao(): FastDao
    abstract fun progressPhotoDao(): ProgressPhotoDao
    abstract fun profileDao(): ProfileDao
    abstract fun foodDao(): FoodDao

    /**
     * Creates the profile every existing row was just handed to.
     *
     * The column default puts every old row in profile 1 before this runs, so the row has to
     * exist with that identifier or the whole history becomes invisible on the first launch
     * after the update.
     */
    class AddProfiles : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO profiles (id, name, position, createdAtUtcMillis) " +
                    "VALUES (" + DEFAULT_PROFILE_ID + ", '" + DEFAULT_PROFILE_NAME + "', 0, 0)",
            )
        }
    }

    companion object {
        const val NAME = "weighttrack.db"

        /** Where every reading taken before profiles existed lives. */
        const val DEFAULT_PROFILE_ID = 1L
        const val DEFAULT_PROFILE_NAME = "Me"
    }
}
