package com.weighttrack.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.weighttrack.data.db.FastDao
import com.weighttrack.data.db.GoalDao
import com.weighttrack.data.db.DeletionDao
import com.weighttrack.data.db.FoodDao
import com.weighttrack.data.db.FoodLogDao
import com.weighttrack.data.db.MacroTargetDao
import com.weighttrack.data.db.ProfileDao
import com.weighttrack.data.db.ProgressPhotoDao
import com.weighttrack.data.db.MeasurementDao
import com.weighttrack.data.db.WaterDao
import com.weighttrack.data.db.WeightEntryDao
import com.weighttrack.data.db.SyncDao
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.diagnostics.CrashLogStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WeightTrackDatabase =
        Room.databaseBuilder(context, WeightTrackDatabase::class.java, WeightTrackDatabase.NAME)
            // Write-ahead logging keeps a reader from blocking the write that happens the
            // instant someone steps off the scale, and survives a process kill mid-write.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            // A database created fresh has no profiles, and the migration only seeds one for an
            // upgrade. Without this the switcher would be empty on a new install even though
            // every reading was going to profile one anyway.
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO profiles " +
                                "(id, name, position, createdAtUtcMillis) VALUES (" +
                                WeightTrackDatabase.DEFAULT_PROFILE_ID + ", '" +
                                WeightTrackDatabase.DEFAULT_PROFILE_NAME + "', 0, 0)",
                        )
                    }
                },
            )
            .build()

    @Provides
    fun provideWeightEntryDao(database: WeightTrackDatabase): WeightEntryDao =
        database.weightEntryDao()

    @Provides
    fun provideMeasurementDao(database: WeightTrackDatabase): MeasurementDao =
        database.measurementDao()

    @Provides
    fun provideGoalDao(database: WeightTrackDatabase): GoalDao = database.goalDao()

    @Provides
    fun provideWaterDao(database: WeightTrackDatabase): WaterDao = database.waterDao()

    @Provides
    fun provideFastDao(database: WeightTrackDatabase): FastDao = database.fastDao()

    @Provides
    fun provideProgressPhotoDao(database: WeightTrackDatabase): ProgressPhotoDao =
        database.progressPhotoDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("weighttrack_settings")
        }

    /**
     * Crash reports live in internal storage, which no other app can read and which the
     * data-extraction rules already exclude from backup and device transfer.
     */
    @Provides
    @Singleton
    fun provideCrashLogStore(@ApplicationContext context: Context): CrashLogStore =
        CrashLogStore(File(context.filesDir, CrashLogStore.DIRECTORY_NAME))

    @Provides
    fun provideProfileDao(database: WeightTrackDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideFoodDao(database: WeightTrackDatabase): FoodDao = database.foodDao()

    @Provides
    fun provideFoodLogDao(database: WeightTrackDatabase): FoodLogDao = database.foodLogDao()

    @Provides
    fun provideMacroTargetDao(database: WeightTrackDatabase): MacroTargetDao =
        database.macroTargetDao()

    @Provides
    fun provideDeletionDao(database: WeightTrackDatabase): DeletionDao = database.deletionDao()

    @Provides
    fun provideSyncDao(database: WeightTrackDatabase): SyncDao = database.syncDao()
}
