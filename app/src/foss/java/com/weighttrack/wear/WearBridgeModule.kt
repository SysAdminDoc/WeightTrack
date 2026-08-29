package com.weighttrack.wear

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The F-Droid build has no Google dependency, so there is no Data Layer and no watch.
 *
 * Deliberately not a shared default: leaving the binding to each flavour means a new flavour
 * fails to compile rather than silently shipping a watch that never receives anything.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WearBridgeModule {
    @Binds
    @Singleton
    abstract fun bindWearBridge(bridge: NoWearBridge): WearBridge
}
