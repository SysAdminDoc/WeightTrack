package com.weighttrack.core.scale

import java.util.UUID

/**
 * A scale that does not speak the Bluetooth standard services.
 *
 * Each vendor invented its own frames, so each one is a small state machine: bytes come in,
 * bytes and readings come out. Keeping it that way means the whole protocol can be driven from
 * a test with no radio, which matters here because none of this hardware is on hand.
 *
 * Implementations are re-implemented from published protocol descriptions, chiefly openScale's.
 * Where two sources disagreed, the comment on the implementation says which was followed and
 * why. Where nothing corroborated a layout, it is not implemented at all rather than guessed.
 */
interface VendorScaleProtocol {

    /** What to call this family on screen. */
    val name: String

    /** The service the scale advertises and everything below lives under. */
    val serviceUuid: UUID

    /** The characteristic the scale talks on. */
    val notifyUuid: UUID

    /** The characteristic to write to, when it is a different one. */
    val writeUuid: UUID get() = notifyUuid

    /** Whether an advertised name looks like this family. */
    fun handles(deviceName: String?): Boolean

    /** What to send the moment the connection is up. */
    fun onConnected(nowUtcMillis: Long): List<ByteArray>

    /** What a notification means: what to send back, and anything it finished. */
    fun onNotification(bytes: ByteArray, nowUtcMillis: Long): VendorStep
}

/** The result of feeding one notification to a protocol. */
data class VendorStep(
    val writes: List<ByteArray> = emptyList(),
    /** A weight that is still moving about, for the screen to show while someone settles. */
    val liveGrams: Int? = null,
    /** Finished readings, ready to record. */
    val readings: List<ScaleReading> = emptyList(),
)

/** A sixteen bit Bluetooth identifier as the full thing it stands for. */
fun shortBluetoothUuid(short: Int): UUID =
    UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", short))

/** Reads unsigned big-endian fields, which is what these vendors mostly use. */
internal fun ByteArray.u8(offset: Int): Int? =
    if (offset in indices) this[offset].toInt() and 0xFF else null

internal fun ByteArray.u16be(offset: Int): Int? =
    if (offset + 1 in indices) {
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
    } else {
        null
    }

internal fun ByteArray.u16le(offset: Int): Int? =
    if (offset + 1 in indices) {
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    } else {
        null
    }

internal fun ByteArray.u32be(offset: Int): Long? =
    if (offset + 3 in indices) {
        var value = 0L
        for (i in offset..offset + 3) value = (value shl 8) or (this[i].toLong() and 0xFF)
        value
    } else {
        null
    }

internal fun Long.toBytesBe(count: Int): ByteArray =
    ByteArray(count) { index -> ((this shr ((count - 1 - index) * 8)) and 0xFF).toByte() }
