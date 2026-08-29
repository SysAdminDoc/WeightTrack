package com.weighttrack.core.scale

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StandardScaleParserTest {

    private fun bytes(vararg values: Int): ByteArray =
        values.map { it.toByte() }.toByteArray()

    /** Little-endian, which every numeric field in both characteristics is. */
    private fun u16(value: Int): List<Int> = listOf(value and 0xFF, (value shr 8) and 0xFF)

    private fun weightPacket(flags: Int, vararg rest: Int) =
        bytes(flags, *rest)

    @Test
    fun `a plain metric weight is five grams a unit`() {
        // 82.5 kg at 0.005 kg resolution is 16500.
        val reading = StandardScaleParser.parseWeightMeasurement(
            weightPacket(0x00, *u16(16_500).toIntArray()),
        )!!

        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.scaleClock).isNull()
        assertThat(reading.scaleUserId).isNull()
    }

    @Test
    fun `an imperial weight converts through pounds`() {
        // 181.9 lb at 0.01 lb resolution is 18190.
        val reading = StandardScaleParser.parseWeightMeasurement(
            weightPacket(0x01, *u16(18_190).toIntArray()),
        )!!

        assertThat(reading.grams).isWithin(20).of(82_508)
    }

    @Test
    fun `the timestamp, user and height fields shift everything after them`() {
        // Flags 0x0E: timestamp, user id, and BMI with height. Nothing here sits at a fixed
        // offset, which is the whole hazard of this format.
        val reading = StandardScaleParser.parseWeightMeasurement(
            weightPacket(
                0x0E,
                *u16(16_500).toIntArray(),
                *u16(2026).toIntArray(), 8, 29, 7, 30, 15,
                3,
                *u16(241).toIntArray(),
                *u16(1_780).toIntArray(),
            ),
        )!!

        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.scaleClock).isEqualTo(ScaleClock(2026, 8, 29, 7, 30, 15))
        assertThat(reading.scaleUserId).isEqualTo(3)
        // BMI is always tenths, whatever the units bit says.
        assertThat(reading.bmi!!).isWithin(1e-9).of(24.1)
        // Metric height is millimetres already.
        assertThat(reading.heightMm).isEqualTo(1_780)
    }

    @Test
    fun `an unknown user slot is reported as no slot rather than as slot 255`() {
        val reading = StandardScaleParser.parseWeightMeasurement(
            weightPacket(0x04, *u16(16_500).toIntArray(), 0xFF),
        )!!

        assertThat(reading.scaleUserId).isNull()
    }

    @Test
    fun `a failed measurement is refused rather than read as 327 kilograms`() {
        // 0xFFFF is the "measurement unsuccessful" marker, and it scales to a weight inside
        // any plausible range, so it has to be caught before the multiplier.
        assertThat(
            StandardScaleParser.parseWeightMeasurement(weightPacket(0x00, 0xFF, 0xFF)),
        ).isNull()
    }

    @Test
    fun `a truncated packet is refused rather than guessed at`() {
        assertThat(StandardScaleParser.parseWeightMeasurement(ByteArray(0))).isNull()
        assertThat(StandardScaleParser.parseWeightMeasurement(bytes(0x00))).isNull()
        assertThat(StandardScaleParser.parseWeightMeasurement(bytes(0x00, 0x74))).isNull()
        // Flags promise a timestamp that is not there.
        assertThat(
            StandardScaleParser.parseWeightMeasurement(weightPacket(0x02, 0x74, 0x40, 0xEA)),
        ).isNull()
    }

    @Test
    fun `body composition flags are two bytes, not one`() {
        // Flags 0x0400 with nothing else: body fat, then weight. Reading one byte of flags
        // would shift every field after it and produce numbers that look plausible.
        val packet = StandardScaleParser.parseBodyComposition(
            bytes(0x00, 0x04, *u16(212).toIntArray(), *u16(16_500).toIntArray()),
        )!!

        assertThat(packet.reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
        assertThat(packet.hasWeight).isTrue()
        assertThat(packet.reading.grams).isEqualTo(82_500)
        assertThat(packet.isSplit).isFalse()
    }

    @Test
    fun `every optional field is read in the order of its flag bit`() {
        val flags = 0x0002 or 0x0004 or 0x0008 or 0x0010 or 0x0020 or 0x0040 or
            0x0080 or 0x0100 or 0x0200 or 0x0400 or 0x0800
        val packet = StandardScaleParser.parseBodyComposition(
            bytes(
                *u16(flags).toIntArray(),
                *u16(212).toIntArray(),
                *u16(2026).toIntArray(), 8, 29, 7, 30, 15,
                2,
                *u16(6_500).toIntArray(),
                *u16(410).toIntArray(),
                *u16(7_000).toIntArray(),
                *u16(13_000).toIntArray(),
                *u16(13_600).toIntArray(),
                *u16(9_000).toIntArray(),
                *u16(5_000).toIntArray(),
                *u16(16_500).toIntArray(),
                *u16(1_780).toIntArray(),
            ),
        )!!
        val reading = packet.reading

        assertThat(reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
        assertThat(reading.scaleClock).isEqualTo(ScaleClock(2026, 8, 29, 7, 30, 15))
        assertThat(reading.scaleUserId).isEqualTo(2)
        // Basal metabolism arrives in kilojoules, not kilocalories.
        assertThat(reading.basalMetabolismKcal!!).isWithin(0.01).of(6_500 / 4.1868)
        assertThat(reading.musclePercent!!).isWithin(1e-9).of(41.0)
        assertThat(reading.muscleMassGrams).isEqualTo(35_000)
        assertThat(reading.fatFreeMassGrams).isEqualTo(65_000)
        assertThat(reading.softLeanMassGrams).isEqualTo(68_000)
        assertThat(reading.bodyWaterMassGrams).isEqualTo(45_000)
        assertThat(reading.impedanceOhms!!).isWithin(1e-9).of(500.0)
        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.heightMm).isEqualTo(1_780)
    }

    @Test
    fun `an unsuccessful field is dropped without dropping the rest`() {
        val flags = 0x0020 or 0x0400
        val packet = StandardScaleParser.parseBodyComposition(
            bytes(
                *u16(flags).toIntArray(),
                0xFF, 0xFF,
                0xFF, 0xFF,
                *u16(16_500).toIntArray(),
            ),
        )!!

        assertThat(packet.reading.bodyFatPercent).isNull()
        assertThat(packet.reading.muscleMassGrams).isNull()
        // The weight still came through, and that is the part worth keeping.
        assertThat(packet.reading.grams).isEqualTo(82_500)
    }

    @Test
    fun `a composition packet without weight says so`() {
        val packet = StandardScaleParser.parseBodyComposition(
            bytes(0x00, 0x00, *u16(212).toIntArray()),
        )!!

        assertThat(packet.hasWeight).isFalse()
        assertThat(packet.reading.grams).isEqualTo(0)
    }

    @Test
    fun `the split bit is reported`() {
        val packet = StandardScaleParser.parseBodyComposition(
            bytes(0x00, 0x10, *u16(212).toIntArray()),
        )!!

        assertThat(packet.isSplit).isTrue()
    }
}
