package com.weighttrack.ble

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.weighttrack.core.scale.BodyCompositionAssembler
import com.weighttrack.core.scale.ScaleReading
import com.weighttrack.core.scale.StandardScaleParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScaleConnectionEvent {
    data object Connected : ScaleConnectionEvent

    /** A finished reading, weight and whatever body composition came with it. */
    data class Measured(val reading: ScaleReading) : ScaleConnectionEvent

    data class Failed(val reason: ScaleProblem) : ScaleConnectionEvent
}

/**
 * Talks to a scale that speaks the standard services.
 *
 * These indicate rather than notify, so the characteristic has to be subscribed to by writing
 * its configuration descriptor; enabling it locally is not enough and is a common reason a scale
 * looks connected and then says nothing.
 */
@Singleton
class ScaleConnection @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun connect(address: String): Flow<ScaleConnectionEvent> = callbackFlow {
        if (!hasConnectPermission()) {
            trySendBlocking(ScaleConnectionEvent.Failed(ScaleProblem.PERMISSION_MISSING))
            close()
            return@callbackFlow
        }
        val manager = ContextCompat.getSystemService(context, BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            trySendBlocking(ScaleConnectionEvent.Failed(ScaleProblem.BLUETOOTH_OFF))
            close()
            return@callbackFlow
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            trySendBlocking(ScaleConnectionEvent.Failed(ScaleProblem.CONNECTION_LOST))
            close()
            return@callbackFlow
        }

        val assembler = BodyCompositionAssembler()
        // Subscribing to more than one characteristic at a time is the classic way to lose an
        // indication: the stack runs one descriptor write at a time.
        val pending = ArrayDeque<BluetoothGattCharacteristic>()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        trySendBlocking(ScaleConnectionEvent.Connected)
                        if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            gatt.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        assembler.flush().forEach {
                            trySendBlocking(ScaleConnectionEvent.Measured(it))
                        }
                        trySendBlocking(ScaleConnectionEvent.Failed(ScaleProblem.CONNECTION_LOST))
                        close()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                pending.clear()
                listOf(
                    WEIGHT_SCALE_SERVICE to WEIGHT_MEASUREMENT,
                    BODY_COMPOSITION_SERVICE to BODY_COMPOSITION_MEASUREMENT,
                ).forEach { (service, characteristic) ->
                    gatt.getService(service)?.getCharacteristic(characteristic)?.let(pending::add)
                }
                if (pending.isEmpty()) {
                    trySendBlocking(ScaleConnectionEvent.Failed(ScaleProblem.CONNECTION_LOST))
                    close()
                } else {
                    subscribeNext(gatt)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                subscribeNext(gatt)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                deliver(characteristic.uuid, value)
            }

            @Deprecated("Kept for Android 12 and earlier, which have no value parameter.")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    deliver(characteristic.uuid, characteristic.value ?: return)
                }
            }

            private fun deliver(uuid: UUID, value: ByteArray) {
                val readings = when (uuid) {
                    WEIGHT_MEASUREMENT ->
                        StandardScaleParser.parseWeightMeasurement(value)
                            ?.let(assembler::onWeightMeasurement)
                            .orEmpty()
                    BODY_COMPOSITION_MEASUREMENT ->
                        StandardScaleParser.parseBodyComposition(value)
                            ?.let(assembler::onBodyComposition)
                            .orEmpty()
                    else -> emptyList()
                }
                readings.forEach { trySendBlocking(ScaleConnectionEvent.Measured(it)) }
            }

            private fun subscribeNext(gatt: BluetoothGatt) {
                val characteristic = pending.removeFirstOrNull() ?: return
                if (
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CLIENT_CONFIG) ?: return
                val enable = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, enable)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = enable
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        val gatt = device.connectGatt(context, false, callback)
        awaitClose {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    gatt?.disconnect()
                    gatt?.close()
                }
            }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        val WEIGHT_SCALE_SERVICE: UUID = shortUuid(0x181D)
        val WEIGHT_MEASUREMENT: UUID = shortUuid(0x2A9D)
        val BODY_COMPOSITION_SERVICE: UUID = shortUuid(0x181B)
        val BODY_COMPOSITION_MEASUREMENT: UUID = shortUuid(0x2A9C)
        val CLIENT_CONFIG: UUID = shortUuid(0x2902)

        fun shortUuid(short: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))
    }
}
