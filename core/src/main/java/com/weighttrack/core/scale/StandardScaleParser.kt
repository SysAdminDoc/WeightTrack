package com.weighttrack.core.scale

import kotlin.math.roundToInt

/**
 * A cursor over a Bluetooth characteristic value.
 *
 * Every numeric field in these two characteristics is an unsigned little-endian integer. Nothing
 * is IEEE-11073 SFLOAT, whatever the health profiles next door do. Running off the end returns
 * null instead of throwing, because a truncated packet from a cheap scale happens and is not
 * worth a crash.
 */
private class ByteCursor(private val bytes: ByteArray) {
    private var offset: Int = 0

    fun u8(): Int? {
        if (offset >= bytes.size) return null
        return bytes[offset++].toInt() and 0xFF
    }

    fun u16(): Int? {
        if (offset + 1 >= bytes.size) return null
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        offset += 2
        return low or (high shl 8)
    }

    fun clock(): ScaleClock? {
        val year = u16() ?: return null
        val month = u8() ?: return null
        val day = u8() ?: return null
        val hour = u8() ?: return null
        val minute = u8() ?: return null
        val second = u8() ?: return null
        return ScaleClock(year, month, day, hour, minute, second)
    }
}

/**
 * The Bluetooth SIG Weight Scale and Body Composition measurements.
 *
 * Both are variable length: only the first field sits at a fixed offset, and everything after it
 * moves depending on which flag bits are set, in exactly the order of the bits. Nothing here may
 * hardcode an offset past the flags.
 */
object StandardScaleParser {

    /** A field carrying this instead of a value means the scale gave up on the measurement. */
    const val UNSUCCESSFUL = 0xFFFF

    /** The scale has user slots but does not know which one this is. */
    private const val UNKNOWN_USER = 0xFF

    private const val GRAMS_PER_LB = 453.59237
    private const val MM_PER_INCH = 25.4
    private const val KJ_PER_KCAL = 4.1868

    /**
     * Weight Measurement, characteristic 0x2A9D.
     *
     * Flags is one byte here. Bit 3 gates two fields at once, BMI then height, and there is no
     * separate height bit.
     */
    fun parseWeightMeasurement(bytes: ByteArray): ScaleReading? {
        val cursor = ByteCursor(bytes)
        val flags = cursor.u8() ?: return null
        val imperial = flags and 0x01 != 0

        val rawWeight = cursor.u16() ?: return null
        // 0xFFFF means the scale gave up, and it scales to a believable 327.675 kg, so it has to
        // be caught before the multiplier rather than by a range check afterwards.
        if (rawWeight == UNSUCCESSFUL) return null

        val clock = if (flags and 0x02 != 0) cursor.clock() ?: return null else null
        val userId = if (flags and 0x04 != 0) (cursor.u8() ?: return null) else null

        var bmi: Double? = null
        var heightMm: Int? = null
        if (flags and 0x08 != 0) {
            val rawBmi = cursor.u16() ?: return null
            val rawHeight = cursor.u16() ?: return null
            // BMI is always tenths, whatever the units bit says. Only mass and height switch.
            bmi = if (rawBmi == UNSUCCESSFUL) null else rawBmi * 0.1
            heightMm = if (rawHeight == UNSUCCESSFUL) null else height(rawHeight, imperial)
        }

        return ScaleReading(
            grams = mass(rawWeight, imperial),
            heightMm = heightMm,
            bmi = bmi,
            scaleUserId = userId?.takeIf { it != UNKNOWN_USER },
            scaleClock = clock,
        )
    }

    /**
     * Body Composition Measurement, characteristic 0x2A9C.
     *
     * Flags is two bytes here, not one. Reading a single byte shifts every field after it and
     * produces numbers that look plausible, which is the classic way to get this wrong.
     *
     * Weight is optional on this characteristic and has its own bit, so a scale may send the
     * composition here and the weight on 0x2A9D. [BodyCompositionAssembler] puts those together.
     */
    fun parseBodyComposition(bytes: ByteArray): BodyCompositionPacket? {
        val cursor = ByteCursor(bytes)
        val flags = cursor.u16() ?: return null
        val imperial = flags and 0x0001 != 0

        val rawFat = cursor.u16() ?: return null
        val fatPercent = if (rawFat == UNSUCCESSFUL) null else rawFat * 0.1

        val clock = if (flags and 0x0002 != 0) cursor.clock() ?: return null else null
        val userId = if (flags and 0x0004 != 0) (cursor.u8() ?: return null) else null

        // The optional fields appear in exactly this order, one per flag bit.
        // Basal metabolism comes over in kilojoules, not kilocalories.
        val basalKcal = optionalScaled(cursor, flags, 0x0008) { it / KJ_PER_KCAL } ?: return null
        val musclePercent = optionalScaled(cursor, flags, 0x0010) { it * 0.1 } ?: return null
        val muscleMass = optionalMass(cursor, flags, 0x0020, imperial) ?: return null
        val fatFreeMass = optionalMass(cursor, flags, 0x0040, imperial) ?: return null
        val softLeanMass = optionalMass(cursor, flags, 0x0080, imperial) ?: return null
        val bodyWaterMass = optionalMass(cursor, flags, 0x0100, imperial) ?: return null
        // Tenths of an ohm. The specification table says hundredths on one line and tenths on
        // the next; tenths is what the older definition and every shipping implementation use.
        val impedance = optionalScaled(cursor, flags, 0x0200) { it * 0.1 } ?: return null
        val weightMass = optionalMass(cursor, flags, 0x0400, imperial) ?: return null
        val heightRaw = optionalRaw(cursor, flags, 0x0800) ?: return null

        return BodyCompositionPacket(
            reading = ScaleReading(
                grams = weightMass.value ?: 0,
                bodyFatPercent = fatPercent,
                muscleMassGrams = muscleMass.value,
                fatFreeMassGrams = fatFreeMass.value,
                softLeanMassGrams = softLeanMass.value,
                bodyWaterMassGrams = bodyWaterMass.value,
                musclePercent = musclePercent.value,
                impedanceOhms = impedance.value,
                basalMetabolismKcal = basalKcal.value,
                heightMm = heightRaw.value?.let { height(it, imperial) },
                scaleUserId = userId?.takeIf { it != UNKNOWN_USER },
                scaleClock = clock,
            ),
            hasWeight = weightMass.value != null,
            isSplit = flags and 0x1000 != 0,
        )
    }

    /** Grams from a mass field, which is the same multiplier on both characteristics. */
    private fun mass(raw: Int, imperial: Boolean): Int =
        if (imperial) (raw * 0.01 * GRAMS_PER_LB).roundToInt() else raw * 5

    private fun height(raw: Int, imperial: Boolean): Int =
        if (imperial) (raw * 0.1 * MM_PER_INCH).roundToInt() else raw

    /**
     * Reads a field when its bit is set.
     *
     * Null means the packet ran out mid-field, which is not the same as the field being absent,
     * so the two cases must not collapse: a [Field] holding null is "the scale did not send it".
     */
    private fun optionalRaw(cursor: ByteCursor, flags: Int, bit: Int): Field<Int>? {
        if (flags and bit == 0) return Field(null)
        val raw = cursor.u16() ?: return null
        return Field(if (raw == UNSUCCESSFUL) null else raw)
    }

    private fun optionalMass(
        cursor: ByteCursor,
        flags: Int,
        bit: Int,
        imperial: Boolean,
    ): Field<Int>? = optionalRaw(cursor, flags, bit)?.let { Field(it.value?.let { r -> mass(r, imperial) }) }

    private fun optionalScaled(
        cursor: ByteCursor,
        flags: Int,
        bit: Int,
        scale: (Double) -> Double,
    ): Field<Double>? = optionalRaw(cursor, flags, bit)?.let { Field(it.value?.let { r -> scale(r.toDouble()) }) }

    private class Field<T>(val value: T?)
}

/** One body composition packet, which may be half of a measurement. */
data class BodyCompositionPacket(
    val reading: ScaleReading,
    val hasWeight: Boolean,
    /** The measurement did not fit in one indication and continues in the next. */
    val isSplit: Boolean,
)
