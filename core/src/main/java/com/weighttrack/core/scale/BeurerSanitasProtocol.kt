package com.weighttrack.core.scale

import java.util.UUID
import kotlin.math.roundToInt

/**
 * Beurer and Sanitas diagnostic scales that do not speak the standard services.
 *
 * BF700, BF710, BF800, SBF70, SBF75 and the Runtastic Libra. The rest of Beurer's range speaks
 * the Bluetooth standard weight and body composition services and is handled by
 * [StandardScaleParser] with nothing vendor-specific needed.
 *
 * Every command starts with a byte made of two nibbles: a family nibble that differs between the
 * BF700 line and the BF710 line, and a command nibble. Get the family nibble wrong and the scale
 * simply never answers.
 *
 * What is implemented here is the passive half: open the link, tell the scale the time, and read
 * the measurements it sends. The parts of the published protocol that create a user on the scale
 * and download its stored history are deliberately left out, because they drive the hardware
 * through a long exchange that cannot be checked without one of these scales on the desk.
 */
class BeurerSanitasProtocol(private val family: Family) : VendorScaleProtocol {

    /** Which line the scale is from, which decides the high nibble of every command. */
    enum class Family(val nibble: Int, val label: String) {
        /** BF700, BF800, Runtastic Libra. */
        BF700(0xF0, "Beurer BF700"),

        /** BF710, SBF70, SBF75. */
        BF710(0xE0, "Beurer BF710"),
    }

    override val name: String get() = family.label

    override val serviceUuid: UUID = shortBluetoothUuid(SERVICE)
    override val notifyUuid: UUID = shortBluetoothUuid(CHARACTERISTIC)

    override fun handles(deviceName: String?): Boolean {
        val text = deviceName?.uppercase() ?: return false
        return NAMES[family]!!.any { text.contains(it) }
    }

    override fun onConnected(nowUtcMillis: Long): List<ByteArray> = listOf(
        // The scale answers this with a frame starting with the same byte, which is how a
        // client knows it picked the right family nibble.
        byteArrayOf(start(INIT).toByte(), 0x01),
        setTime(nowUtcMillis),
    )

    override fun onNotification(bytes: ByteArray, nowUtcMillis: Long): VendorStep {
        val opcode = bytes.u8(1) ?: return VendorStep()
        return when (opcode) {
            LIVE_WEIGHT -> liveWeight(bytes)
            MEASUREMENT, SAVED_MEASUREMENT -> measurement(bytes)
            // Every user record and stored measurement has to be acknowledged or the scale
            // stops sending.
            USER_INFO -> VendorStep(writes = listOf(acknowledge(bytes)))
            else -> VendorStep()
        }
    }

    /** The number on the display while someone is still settling. */
    private fun liveWeight(bytes: ByteArray): VendorStep {
        val raw = bytes.u16be(3) ?: return VendorStep()
        val grams = raw * GRAMS_PER_UNIT
        // Byte two is zero once the reading has stopped moving. An unsettled one is worth
        // showing and not worth storing.
        val settled = bytes.u8(2) == 0
        return if (settled) {
            VendorStep(readings = listOf(ScaleReading(grams = grams)))
        } else {
            VendorStep(liveGrams = grams)
        }
    }

    /**
     * A full measurement, which arrives split across two notifications.
     *
     * The first carries the remote user identifier rather than measurement data, so it is
     * acknowledged and held; the payload starts at offset four in each.
     */
    private fun measurement(bytes: ByteArray): VendorStep {
        val acknowledgement = listOf(acknowledge(bytes))
        val index = bytes.u8(3) ?: return VendorStep(writes = acknowledgement)
        val payload = bytes.copyOfRange(minOf(PAYLOAD_OFFSET, bytes.size), bytes.size)

        if (index == 1) {
            firstHalf = payload
            return VendorStep(writes = acknowledgement)
        }

        val whole = (firstHalf ?: return VendorStep(writes = acknowledgement)) + payload
        firstHalf = null
        val reading = parseMeasurement(whole) ?: return VendorStep(writes = acknowledgement)
        return VendorStep(writes = acknowledgement, readings = listOf(reading))
    }

    private var firstHalf: ByteArray? = null

    private fun acknowledge(bytes: ByteArray): ByteArray = byteArrayOf(
        start(NORMAL).toByte(),
        APP_ACK.toByte(),
        bytes.u8(1)?.toByte() ?: 0,
        bytes.u8(2)?.toByte() ?: 0,
        bytes.u8(3)?.toByte() ?: 0,
    )

    private fun setTime(nowUtcMillis: Long): ByteArray =
        byteArrayOf(start(SET_TIME).toByte()) + (nowUtcMillis / 1000L).toBytesBe(4)

    private fun start(command: Int): Int = family.nibble or command

    companion object {
        const val SERVICE = 0xFFE0
        const val CHARACTERISTIC = 0xFFE1

        /** Weight and bone mass both come in fifty gram units. */
        const val GRAMS_PER_UNIT = 50

        private const val NORMAL = 0x7
        private const val INIT = 0x6
        private const val SET_TIME = 0x9

        private const val APP_ACK = 0xF1
        private const val USER_INFO = 0x34
        private const val SAVED_MEASUREMENT = 0x42
        private const val LIVE_WEIGHT = 0x58
        private const val MEASUREMENT = 0x59

        private const val PAYLOAD_OFFSET = 4

        private val NAMES = mapOf(
            Family.BF700 to listOf("BF700", "BF800", "LIBRA", "BEURER BF70"),
            Family.BF710 to listOf("BF710", "SBF70", "SBF75"),
        )

        /**
         * The measurement payload, once both halves are back together.
         *
         * Everything is unsigned big-endian. The last three fields are the scale's own working
         * out rather than measurements, so they are read for the record but nothing is derived
         * from them.
         */
        fun parseMeasurement(payload: ByteArray): ScaleReading? {
            val rawWeight = payload.u16be(4) ?: return null
            if (rawWeight <= 0) return null
            return ScaleReading(
                grams = rawWeight * GRAMS_PER_UNIT,
                impedanceOhms = payload.u16be(6)?.takeIf { it > 0 }?.toDouble(),
                bodyFatPercent = payload.u16be(8)?.tenths(),
                bodyWaterMassGrams = payload.u16be(10)?.tenths()?.let { percent ->
                    (rawWeight * GRAMS_PER_UNIT * percent / 100.0).roundToInt()
                },
                musclePercent = payload.u16be(12)?.tenths(),
                muscleMassGrams = payload.u16be(12)?.tenths()?.let { percent ->
                    (rawWeight * GRAMS_PER_UNIT * percent / 100.0).roundToInt()
                },
                basalMetabolismKcal = payload.u16be(16)?.takeIf { it > 0 }?.toDouble(),
                bmi = payload.u16be(20)?.tenths(),
            )
        }

        private fun Int.tenths(): Double? = if (this <= 0) null else this * 0.1
    }
}
