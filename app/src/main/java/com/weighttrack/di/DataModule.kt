package com.weighttrack.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import com.weighttrack.data.db.GoalDao
import com.weighttrack.data.db.MeasurementDao
import com.weighttrack.data.db.WeightEntryDao
import com.weighttrack.data.db.WeightTrackDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("weighttrack_settings")
        }
}
