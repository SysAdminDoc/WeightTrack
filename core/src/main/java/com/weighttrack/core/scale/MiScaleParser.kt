package com.weighttrack.core.scale

import kotlin.math.roundToInt

/**
 * Xiaomi's scales, decoded from their advertisement.
 *
 * They reuse the Bluetooth SIG service identifiers as advertisement keys but put their own frame
 * inside, so nothing in [StandardScaleParser] applies. Nothing needs to pair or connect: the
 * weight is in the broadcast, which is why these are worth supporting first among the vendors.
 *
 * The version is told apart by which service identifier carried the data and how long it is, not
 * by the device name. A version 2 frame is not a version 1 frame with extra bytes on the end:
 * the weight moves from the front to the back and the status bits move between bytes.
 */
object MiScaleParser {

    /** Body Composition Service, used by the Mi Body Composition Scale 2. Thirteen bytes. */
    const val SERVICE_V2 = 0x181B

    /** Weight Scale Service, used by the original Mi Smart Scale. Ten bytes. */
    const val SERVICE_V1 = 0x181D

    private const val V2_LENGTH = 13
    private const val V1_LENGTH = 10

    private const val GRAMS_PER_LB = 453.59237

    /** True when a scan result carrying this service and payload is one of these scales. */
    fun handles(serviceUuid16: Int, payload: ByteArray): Boolean = when (serviceUuid16) {
        SERVICE_V2 -> payload.size == V2_LENGTH
        SERVICE_V1 -> payload.size == V1_LENGTH
        else -> false
    }

    fun parse(serviceUuid16: Int, payload: ByteArray): ScaleBroadcast? = when {
        serviceUuid16 == SERVICE_V2 && payload.size == V2_LENGTH -> parseV2(payload)
        serviceUuid16 == SERVICE_V1 && payload.size == V1_LENGTH -> parseV1(payload)
        else -> null
    }

    /**
     * Mi Body Composition Scale 2.
     *
     * Byte 0 bit 0 is pounds, byte 1 bit 6 is jin. Byte 1 also carries stabilized (bit 5),
     * weight removed (bit 7) and whether the impedance has settled (bit 1). Impedance is raw
     * ohms with no divisor, unlike the SIG characteristic's tenths.
     */
    private fun parseV2(payload: ByteArray): ScaleBroadcast {
        val control0 = payload[0].toInt() and 0xFF
        val control1 = payload[1].toInt() and 0xFF
        val unit = when {
            control0 and 0x01 != 0 -> Unit_.LB
            control1 and 0x40 != 0 -> Unit_.JIN
            else -> Unit_.KG
        }
        val impedanceSettled = control1 and 0x02 != 0
        val impedance = u16(payload, 9)
        return ScaleBroadcast(
            reading = ScaleReading(
                grams = grams(u16(payload, 11), unit),
                impedanceOhms = if (impedanceSettled && impedance > 0) impedance.toDouble() else null,
                scaleClock = ScaleClock(
                    year = u16(payload, 2),
                    month = payload[4].toInt() and 0xFF,
                    day = payload[5].toInt() and 0xFF,
                    hour = payload[6].toInt() and 0xFF,
                    minute = payload[7].toInt() and 0xFF,
                    second = payload[8].toInt() and 0xFF,
                ),
            ),
            stabilized = control1 and 0x20 != 0,
            weightRemoved = control1 and 0x80 != 0,
        )
    }

    /**
     * The original Mi Smart Scale.
     *
     * Every status bit is in byte 0 here, and jin is bit 4 rather than bit 6 of byte 1. There is
     * no impedance. Bit 1 is commonly set on these and means nothing useful, so it is ignored.
     */
    private fun parseV1(payload: ByteArray): ScaleBroadcast {
        val control = payload[0].toInt() and 0xFF
        val unit = when {
            control and 0x01 != 0 -> Unit_.LB
            control and 0x10 != 0 -> Unit_.JIN
            else -> Unit_.KG
        }
        return ScaleBroadcast(
            reading = ScaleReading(
                grams = grams(u16(payload, 1), unit),
                scaleClock = ScaleClock(
                    year = u16(payload, 3),
                    month = payload[5].toInt() and 0xFF,
                    day = payload[6].toInt() and 0xFF,
                    hour = payload[7].toInt() and 0xFF,
                    minute = payload[8].toInt() and 0xFF,
                    second = payload[9].toInt() and 0xFF,
                ),
            ),
            stabilized = control and 0x20 != 0,
            weightRemoved = control and 0x80 != 0,
        )
    }

    private enum class Unit_ { KG, LB, JIN }

    /** The raw value is the weight times 200 in kilograms, or times 100 in pounds or jin. */
    private fun grams(raw: Int, unit: Unit_): Int = when (unit) {
        Unit_.KG -> raw * 5
        Unit_.LB -> (raw * 0.01 * GRAMS_PER_LB).roundToInt()
        // A jin is exactly half a kilogram.
        Unit_.JIN -> raw * 5
    }

    private fun u16(payload: ByteArray, offset: Int): Int =
        (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
}
