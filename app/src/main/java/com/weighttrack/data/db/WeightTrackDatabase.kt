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
        FoodLogEntryEntity::class,
        MacroTargetEntity::class,
        DeletionEntity::class,
    ],
    version = 10,
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
        // Six adds the food tables and seven the food log, both only new tables, so Room
        // handles them alone.
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        // Nine gives every row that can be synced a name that travels, and adds the table that
        // remembers deletions.
        AutoMigration(from = 8, to = 9, spec = WeightTrackDatabase.AddSyncIds::class),
        // Ten adds the profile to a deletion, because a record's name is only unique within one.
        AutoMigration(from = 9, to = 10),
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
    abstract fun foodLogDao(): FoodLogDao
    abstract fun macroTargetDao(): MacroTargetDao
    abstract fun deletionDao(): DeletionDao
    abstract fun syncDao(): SyncDao

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

    /**
     * Names every existing row so it can be synced.
     *
     * The column default is blank, because SQL cannot give each row a different one. This fills
     * them in afterwards: randomblob is per row, so every row gets its own. Leaving them blank
     * would make every row on the phone look like the same record to the merge, and the first
     * sync would collapse a whole history into one reading.
     */
    class AddSyncIds : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            for (table in SYNCED_TABLES) {
                db.execSQL(
                    "UPDATE $table SET syncId = lower(hex(randomblob(16))) " +
                        "WHERE syncId IS NULL OR syncId = ''",
                )
            }
            // Rows that existed before sync have no time last touched. Their creation time is
            // the truthful answer; a zero would make anything arriving from another device look
            // newer than a goal set this morning.
            db.execSQL(
                "UPDATE profiles SET updatedAtUtcMillis = createdAtUtcMillis " +
                    "WHERE updatedAtUtcMillis = 0",
            )
            db.execSQL(
                "UPDATE goals SET updatedAtUtcMillis = createdAtUtcMillis " +
                    "WHERE updatedAtUtcMillis = 0",
            )
        }
    }

    companion object {
        const val NAME = "weighttrack.db"

        /** Every table whose rows carry a name that travels between devices. */
        val SYNCED_TABLES = listOf(
            "profiles", "measurements", "goals", "water_entries", "fasts", "macro_targets",
        )

        /** Where every reading taken before profiles existed lives. */
        const val DEFAULT_PROFILE_ID = 1L
        const val DEFAULT_PROFILE_NAME = "Me"
    }
}
