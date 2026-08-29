package com.weighttrack.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.weighttrack.core.scale.MiScaleParser
import com.weighttrack.core.scale.ScaleBroadcast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A scale seen on the air. */
data class ScaleDevice(
    val address: String,
    val name: String?,
    val kind: ScaleKind,
) {
    /** What to call it on screen when the scale did not say. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "Unnamed scale"
}

enum class ScaleKind {
    /** Puts the weight in its advertisement. Nothing to pair, nothing to connect to. */
    BROADCAST,

    /** Speaks the standard weight or body composition service over a connection. */
    STANDARD_SERVICE,
}

sealed interface ScaleScanEvent {
    data class Found(val device: ScaleDevice) : ScaleScanEvent

    /** A weight straight off the air, which is usually still settling. */
    data class Broadcast(val device: ScaleDevice, val broadcast: ScaleBroadcast) : ScaleScanEvent

    data class Failed(val reason: ScaleProblem) : ScaleScanEvent
}

/** Why a scan or a connection could not happen, in terms a screen can explain. */
enum class ScaleProblem {
    NO_BLUETOOTH_HARDWARE,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    SCAN_FAILED,
    CONNECTION_LOST,
}

/**
 * Listens for scales.
 *
 * Two kinds turn up. Xiaomi's put the weight in the advertisement, so a scan is the whole
 * conversation. Everything else has to be connected to, and the scan only finds it.
 */
@Singleton
class ScaleScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val adapter: BluetoothAdapter?
        get() = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter

    /** Whether this build could scan right now, and if not, why not. */
    fun problem(): ScaleProblem? {
        val adapter = adapter ?: return ScaleProblem.NO_BLUETOOTH_HARDWARE
        if (!hasScanPermission()) return ScaleProblem.PERMISSION_MISSING
        if (!adapter.isEnabled) return ScaleProblem.BLUETOOTH_OFF
        return null
    }

    fun hasScanPermission(): Boolean = requiredPermissions().all { granted(it) }

    /**
     * The permissions a scan needs on this Android version.
     *
     * Before Android 12 a scan counted as a location request, which is why the older versions
     * ask for a location permission to read a weight.
     */
    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun scan(): Flow<ScaleScanEvent> = callbackFlow {
        problem()?.let {
            trySendBlocking(ScaleScanEvent.Failed(it))
            close()
            return@callbackFlow
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySendBlocking(ScaleScanEvent.Failed(ScaleProblem.BLUETOOTH_OFF))
            close()
            return@callbackFlow
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emit)
            }

            override fun onScanFailed(errorCode: Int) {
                trySendBlocking(ScaleScanEvent.Failed(ScaleProblem.SCAN_FAILED))
            }

            private fun emit(result: ScanResult) {
                val record = result.scanRecord ?: return
                // Reading the name needs the connect permission on Android 12 and later, and
                // lint does not follow the check through a helper, so it is repeated here.
                val name = if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    granted(Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    record.deviceName
                } else {
                    null
                }

                val broadcast = SERVICE_UUIDS.firstNotNullOfOrNull { (short, uuid) ->
                    record.serviceData[ParcelUuid(uuid)]?.let { payload ->
                        MiScaleParser.parse(short, payload)
                    }
                }
                val kind = if (broadcast != null) ScaleKind.BROADCAST else ScaleKind.STANDARD_SERVICE
                val device = ScaleDevice(result.device.address, name, kind)

                trySendBlocking(ScaleScanEvent.Found(device))
                broadcast?.let { trySendBlocking(ScaleScanEvent.Broadcast(device, it)) }
            }
        }

        val filters = SERVICE_UUIDS.map { (_, uuid) ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }
        val settings = ScanSettings.Builder()
            // Someone is standing in front of the scale waiting, so latency matters more than
            // the radio budget for the half minute this runs.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Repeated here rather than left to problem() above: lint does not follow a permission
        // check through a helper, and this call genuinely throws without the grant.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySendBlocking(ScaleScanEvent.Failed(ScaleProblem.PERMISSION_MISSING))
            close()
            return@callbackFlow
        }
        scanner.startScan(filters, settings, callback)

        awaitClose {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { scanner.stopScan(callback) }
            }
        }
    }

    private companion object {
        /** The two service identifiers a scale advertises under, standard or Xiaomi. */
        val SERVICE_UUIDS = listOf(
            MiScaleParser.SERVICE_V1 to shortUuid(MiScaleParser.SERVICE_V1),
            MiScaleParser.SERVICE_V2 to shortUuid(MiScaleParser.SERVICE_V2),
        )

        fun shortUuid(short: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))
    }
}
