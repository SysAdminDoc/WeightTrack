package com.weighttrack.wear

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WearBridgeModule {
    @Binds
    @Singleton
    abstract fun bindWearBridge(bridge: PlayWearBridge): WearBridge
}
