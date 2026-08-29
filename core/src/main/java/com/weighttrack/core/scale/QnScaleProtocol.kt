package com.weighttrack.core.scale

import java.util.UUID

/**
 * The Qingniu protocol, sold under Renpho, QN-Scale, Yolanda and SEB badges.
 *
 * The handshake is scale-driven: nothing is written on connecting. The scale announces itself
 * with a frame that says which protocol variant it is speaking and how finely it reports weight,
 * and only then is there anything worth sending back.
 *
 * Renpho also ships two other, unrelated protocols under the same brand. The ES-CS20M family
 * speaks something else entirely, and there is a broadcast-only variant whose byte offsets no
 * source agrees on. Neither is here.
 */
class QnScaleProtocol : VendorScaleProtocol {

    override val name: String = "Renpho / QN-Scale"
    override val serviceUuid: UUID = shortBluetoothUuid(SERVICE_FFE0)
    override val notifyUuid: UUID = shortBluetoothUuid(NOTIFY_FFE1)
    override val writeUuid: UUID = shortBluetoothUuid(WRITE_FFE3)

    /** Echoed back in every reply. Zero until the scale has said what it is. */
    private var protocolType: Int = 0

    /** The scale reports either hundredths or tenths of a kilogram, and it says which. */
    private var divisor: Int = 10

    override fun handles(deviceName: String?): Boolean {
        val text = deviceName?.uppercase() ?: return false
        return text.contains("QN-SCALE") || text.contains("RENPHO") || text.contains("SEB-SCALE")
    }

    /** Nothing. Writing before the scale has introduced itself is what makes this go wrong. */
    override fun onConnected(nowUtcMillis: Long): List<ByteArray> = emptyList()

    override fun onNotification(bytes: ByteArray, nowUtcMillis: Long): VendorStep =
        when (bytes.u8(0)) {
            HELLO -> hello(bytes, nowUtcMillis)
            LIVE_WEIGHT -> liveWeight(bytes)
            else -> VendorStep()
        }

    private fun hello(bytes: ByteArray, nowUtcMillis: Long): VendorStep {
        protocolType = bytes.u8(2) ?: 0
        divisor = if (bytes.u8(10) == 1) 100 else 10
        return VendorStep(
            writes = listOf(
                // Kilograms, because everything is stored in grams and converted for display.
                checksummed(byteArrayOf(0x13, 0x09, protocolType.toByte(), 0x01, 0x10, 0, 0, 0)),
                setTime(nowUtcMillis),
            ),
        )
    }

    /**
     * The weight as it settles.
     *
     * Two layouts exist. The published discriminator looks at byte four, which can only be a
     * state marker when it is small enough not to be the high half of a plausible weight.
     */
    private fun liveWeight(bytes: ByteArray): VendorStep {
        val state = bytes.u8(4)
        val extended = state != null && state <= 0x02 && divisor == 10
        val raw = (if (extended) bytes.u16be(5) else bytes.u16be(3)) ?: return VendorStep()
        val settled = if (extended) state == 0x01 || state == 0x02 else bytes.u8(5) == 1
        val impedance = (if (extended) bytes.u16be(7) else bytes.u16be(6))?.takeIf { it > 0 }

        val grams = grams(raw) ?: return VendorStep()
        return if (settled) {
            VendorStep(
                readings = listOf(
                    ScaleReading(grams = grams, impedanceOhms = impedance?.toDouble()),
                ),
            )
        } else {
            VendorStep(liveGrams = grams)
        }
    }

    private fun grams(raw: Int): Int? {
        val grams = raw * 1000 / divisor
        // A scale that reported the wrong resolution lands an order of magnitude out. Rather
        // than the published habit of dividing again until the number looks nice, an impossible
        // weight is refused: a silently rescaled reading is worse than no reading.
        return grams.takeIf { it in MIN_GRAMS..MAX_GRAMS }
    }

    private fun setTime(nowUtcMillis: Long): ByteArray {
        val seconds = (nowUtcMillis / 1000L) - EPOCH_2000_SECONDS
        return byteArrayOf(
            0x02,
            (seconds and 0xFF).toByte(),
            ((seconds shr 8) and 0xFF).toByte(),
            ((seconds shr 16) and 0xFF).toByte(),
            ((seconds shr 24) and 0xFF).toByte(),
        )
    }

    companion object {
        const val SERVICE_FFE0 = 0xFFE0
        const val NOTIFY_FFE1 = 0xFFE1
        const val WRITE_FFE3 = 0xFFE3

        /** The other layout these scales come in. */
        const val SERVICE_FFF0 = 0xFFF0
        const val NOTIFY_FFF1 = 0xFFF1
        const val WRITE_FFF2 = 0xFFF2

        private const val HELLO = 0x12
        private const val LIVE_WEIGHT = 0x10

        private const val MIN_GRAMS = 20_000
        private const val MAX_GRAMS = 400_000

        /**
         * The scale counts from the start of 2000.
         *
         * Not the value openScale uses. Its constant is five hours later, which is midnight in
         * New York rather than midnight UTC; the error cancels in its own comparisons but any
         * absolute time read with it is out by five hours.
         */
        const val EPOCH_2000_SECONDS = 946_684_800L

        /** The low byte of the sum of everything before it. */
        fun checksummed(body: ByteArray): ByteArray =
            body + body.fold(0) { total, byte -> total + (byte.toInt() and 0xFF) }.and(0xFF).toByte()
    }
}
