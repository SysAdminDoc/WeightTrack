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
import com.weighttrack.diagnostics.RuntimeLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** The one interface in the app, so the hourly job can be driven without a phone. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncWorkModule {
    @dagger.Binds
    @Singleton
    abstract fun syncWork(real: com.weighttrack.sync.RealSyncWork): com.weighttrack.sync.SyncWork

    @dagger.Binds
    abstract fun haptics(
        real: com.weighttrack.ui.scale.AndroidHaptics,
    ): com.weighttrack.ui.scale.Haptics
}

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

    /**
     * The runtime log, beside the crash reports and covered by the same exclusions.
     *
     * One file for the whole app, so the order of events across sync, Health Connect and a scale
     * is the order they actually happened in. That ordering is most of its value.
     */
    @Provides
    @Singleton
    fun provideRuntimeLog(@ApplicationContext context: Context): RuntimeLog =
        RuntimeLog(File(context.filesDir, RuntimeLog.FILE_NAME))

    /**
     * The real Health Connect client, when this phone has one worth talking to.
     */
    @Provides
    @Singleton
    fun provideHealthConnectClientSource(
        @ApplicationContext context: Context,
    ): com.weighttrack.health.HealthConnectClientSource =
        com.weighttrack.health.HealthConnectClientSource {
            if (
                androidx.health.connect.client.HealthConnectClient.getSdkStatus(context) ==
                androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE
            ) {
                runCatching {
                    androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
                }.getOrNull()
            } else {
                null
            }
        }

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

    @Provides
    fun provideSyncPeerDao(database: WeightTrackDatabase): com.weighttrack.data.db.SyncPeerDao =
        database.syncPeerDao()

    @Provides
    fun provideMedicationDoseDao(
        database: WeightTrackDatabase,
    ): com.weighttrack.data.db.MedicationDoseDao = database.medicationDoseDao()

    @Provides
    fun provideSideEffectDao(
        database: WeightTrackDatabase,
    ): com.weighttrack.data.db.SideEffectDao = database.sideEffectDao()
}
