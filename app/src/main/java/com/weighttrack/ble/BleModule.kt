package com.weighttrack.ble

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class BleModule {

    @Binds
    @Singleton
    abstract fun bindScaleScanner(scanner: BluetoothScaleScanner): ScaleScanner

    @Binds
    @Singleton
    abstract fun bindScaleConnection(connection: BluetoothScaleConnection): ScaleConnection
}
