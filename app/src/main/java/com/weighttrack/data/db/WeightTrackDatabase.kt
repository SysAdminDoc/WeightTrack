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
        SyncPeerEntity::class,
    ],
    version = 20,
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
        // Eleven gives the food tables a name that travels, so a phone switch carries the diary.
        AutoMigration(from = 10, to = 11, spec = WeightTrackDatabase.AddFoodSyncIds::class),
        // Twelve marks each weigh-in with the version of it Health Connect has been told about,
        // so the export sends what has changed rather than everything, every hour, for ever.
        AutoMigration(from = 11, to = 12),
        // Thirteen moves height, sex, year of birth and activity level onto the profile they
        // describe. They arrive blank; the values that were in the app's settings are handed to
        // whoever was active at the time, once, by ProfileRepository.adoptLegacyDemographics.
        AutoMigration(from = 12, to = 13),
        // Fourteen keeps what a body-composition scale sends beyond the weight, which every
        // parser read and every save threw away.
        AutoMigration(from = 13, to = 14),
        // Fifteen keeps the height a standards-compliant scale reports, which the parser has
        // always read and the save had no column for.
        AutoMigration(from = 14, to = 15),
        // Sixteen records which app in Health Connect wrote an imported reading, and on what.
        AutoMigration(from = 15, to = 16),
        // Seventeen holds the band a goal is judged against, which was a constant.
        AutoMigration(from = 16, to = 17),
        // Eighteen remembers when a looked-up product was last read, so a stale one can say so.
        AutoMigration(from = 17, to = 18),
        // Nineteen marks a measurement carried forward from the last set rather than measured
        // again, so a set can be complete without every value claiming to be today's.
        AutoMigration(from = 18, to = 19),
        // Twenty stamps every syncable row with the device that made the version of it that is
        // here, and adds the table of devices this one syncs with. Together they replace a
        // deletion rule that ran on the calendar with one that runs on evidence.
        AutoMigration(from = 19, to = 20),
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
    abstract fun syncPeerDao(): SyncPeerDao

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

    /**
     * Names the food rows so they can travel, the same way [AddSyncIds] did for the rest.
     *
     * Blank would make every row look like the same record to the merge, and a first sync would
     * collapse a whole food database into one entry.
     */
    class AddFoodSyncIds : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            for (table in FOOD_TABLES) {
                db.execSQL(
                    "UPDATE $table SET syncId = lower(hex(randomblob(16))) " +
                        "WHERE syncId IS NULL OR syncId = ''",
                )
            }
            // A diary entry has no time of its own. When it was eaten is the truthful answer;
            // a zero would make anything arriving from another device look newer.
            db.execSQL(
                "UPDATE food_log_entries SET updatedAtUtcMillis = loggedAtUtcMillis " +
                    "WHERE updatedAtUtcMillis = 0",
            )
        }
    }

    companion object {
        const val NAME = "weighttrack.db"

        /** The food tables, whose rows also carry a name that travels. */
        val FOOD_TABLES = listOf("foods", "recipes", "recipe_items", "food_log_entries")

        /** Every table whose rows carry a name that travels between devices. */
        val SYNCED_TABLES = listOf(
            "profiles", "measurements", "goals", "water_entries", "fasts", "macro_targets",
        )

        /** Where every reading taken before profiles existed lives. */
        const val DEFAULT_PROFILE_ID = 1L
        const val DEFAULT_PROFILE_NAME = "Me"
    }
}
