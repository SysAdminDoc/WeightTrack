package com.weighttrack.core.scale

import java.util.UUID

/**
 * Anker's eufy scales, in the two shapes that are documented well enough to write down.
 *
 * The C1 and P1 speak the 1byone protocol on service FFF0. The BodySense speaks a different one
 * on a vendor service, with every frame starting AC 02. Neither authenticates.
 *
 * The P2 and P2 Pro are not here: they require an AES handshake keyed on the device address, and
 * the one field that would make them worth the work, the impedance, is at an offset the sources
 * disagree about. The C20's weight is documented but its impedance and heart rate come from a
 * single uncorroborated implementation, so only the weight is read.
 */
object EufyProtocols {

    /**
     * The 1byone protocol, used by the eufy Smart Scale C1 and P1.
     *
     * The measurement frame is the interesting part: it arrives whole, so there is no state to
     * keep between notifications.
     */
    class OneByOne : VendorScaleProtocol {
        override val name: String = "eufy C1 / P1"
        override val serviceUuid: UUID = shortBluetoothUuid(SERVICE)
        override val notifyUuid: UUID = shortBluetoothUuid(NOTIFY)
        override val writeUuid: UUID = shortBluetoothUuid(WRITE)

        override fun handles(deviceName: String?): Boolean {
            val text = deviceName?.uppercase() ?: return false
            return text.contains("EUFY") || text.contains("1BYONE") || text.startsWith("T91")
        }

        override fun onConnected(nowUtcMillis: Long): List<ByteArray> = listOf(
            // Unit and user group. The last byte is an exclusive or of the ten before it, not a
            // sum: this is the one place in this family that is not a checksum.
            withXor(byteArrayOf(0xFD.toByte(), 0x37, 0x00, 0x01, 0, 0, 0, 0, 0, 0)),
        )

        override fun onNotification(bytes: ByteArray, nowUtcMillis: Long): VendorStep {
            if (bytes.u8(0) != MEASUREMENT) return VendorStep()
            val raw = bytes.u16le(3) ?: return VendorStep()
            val grams = raw * 10
            if (grams <= 0) return VendorStep()

            // Byte nine says whether the reading has finished. One means there was no impedance
            // to report; two means the scale gave up because the weight was over its limit.
            return when (bytes.u8(9)) {
                FINAL -> VendorStep(readings = listOf(ScaleReading(grams = grams)))
                WITHOUT_IMPEDANCE -> VendorStep(readings = listOf(ScaleReading(grams = grams)))
                else -> VendorStep(liveGrams = grams)
            }
        }

        companion object {
            const val SERVICE = 0xFFF0
            const val NOTIFY = 0xFFF4
            const val WRITE = 0xFFF1

            private const val MEASUREMENT = 0xCF
            private const val FINAL = 0x00
            private const val WITHOUT_IMPEDANCE = 0x01
        }
    }

    /**
     * The eufy Smart Scale, sold as BodySense.
     *
     * Every frame starts AC 02 and carries a checksum of everything after that prefix. The
     * weight is tenths of a kilogram big-endian, which is coarser than the rest of the scales
     * here and is what the hardware reports.
     */
    class BodySense : VendorScaleProtocol {
        override val name: String = "eufy BodySense"
        // A full custom identifier, not a short one dressed up as the base.
        override val serviceUuid: UUID = UUID.fromString(SERVICE_UUID)
        override val notifyUuid: UUID = UUID.fromString(NOTIFY_UUID)
        override val writeUuid: UUID = UUID.fromString(WRITE_UUID)

        override fun handles(deviceName: String?): Boolean {
            val text = deviceName?.uppercase() ?: return false
            return text.contains("EUFY") || text.contains("T9140")
        }

        override fun onConnected(nowUtcMillis: Long): List<ByteArray> = listOf(
            timeSync(nowUtcMillis),
        )

        override fun onNotification(bytes: ByteArray, nowUtcMillis: Long): VendorStep {
            // Two sub-frames arrive glued together often enough that it is worth splitting.
            val marker = bytes.u8(6)
            val raw = bytes.u16be(2) ?: return VendorStep()
            return when {
                marker == FINAL_WEIGHT -> {
                    val grams = raw * 100
                    if (grams > 0) {
                        VendorStep(readings = listOf(ScaleReading(grams = grams)))
                    } else {
                        VendorStep()
                    }
                }
                marker == LIVE_WEIGHT -> VendorStep(liveGrams = raw * 100)
                else -> VendorStep()
            }
        }

        companion object {
            const val SERVICE_UUID = "4143f6b0-5300-4900-4700-414943415245"
            const val NOTIFY_UUID = "4143f6b2-5300-4900-4700-414943415245"
            const val WRITE_UUID = "4143f6b1-5300-4900-4700-414943415245"

            private const val FINAL_WEIGHT = 0xCA
            private const val LIVE_WEIGHT = 0xCE

            /** The timestamp is little-endian, whatever the write-ups say; the worked examples are. */
            fun timeSync(nowUtcMillis: Long): ByteArray {
                val seconds = nowUtcMillis / 1000L
                val body = byteArrayOf(
                    0xF2.toByte(),
                    (seconds and 0xFF).toByte(),
                    ((seconds shr 8) and 0xFF).toByte(),
                    ((seconds shr 16) and 0xFF).toByte(),
                    ((seconds shr 24) and 0xFF).toByte(),
                    0xCC.toByte(),
                )
                return byteArrayOf(0xAC.toByte(), 0x02) + body + checksum(body)
            }

            /** The sum of everything after the two byte prefix, with the low byte kept. */
            fun checksum(body: ByteArray): Byte =
                body.fold(0) { total, byte -> total + (byte.toInt() and 0xFF) }.and(0xFF).toByte()
        }
    }

    /** Appends the exclusive or of every byte, which is what the 1byone commands carry. */
    internal fun withXor(bytes: ByteArray): ByteArray =
        bytes + bytes.fold(0) { total, byte -> total xor (byte.toInt() and 0xFF) }.toByte()
}
