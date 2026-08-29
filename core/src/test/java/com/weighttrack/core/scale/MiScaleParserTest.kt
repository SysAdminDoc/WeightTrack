package com.weighttrack.core.scale

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MiScaleParserTest {

    private fun bytes(hex: String): ByteArray =
        hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `a version 2 frame decodes to the published weight`() {
        // Test vector published with the xiaomi-ble parser: 85.15 kg, 428 ohms.
        val broadcast = MiScaleParser.parse(
            MiScaleParser.SERVICE_V2,
            bytes("02 26 B2 07 05 04 0F 02 01 AC 01 86 42"),
        )!!

        assertThat(broadcast.reading.grams).isEqualTo(85_150)
        assertThat(broadcast.reading.impedanceOhms).isEqualTo(428.0)
        assertThat(broadcast.stabilized).isTrue()
        assertThat(broadcast.weightRemoved).isFalse()
        assertThat(broadcast.isFinal).isTrue()
        // The year in this frame is 1970, which is the point: scale clocks are usually unset
        // or wrong, so the clock is kept only to recognise a repeated broadcast.
        assertThat(broadcast.reading.scaleClock)
            .isEqualTo(ScaleClock(1970, 5, 4, 15, 2, 1))
    }

    @Test
    fun `a version 1 frame decodes to the published weight`() {
        // Test vector published with the xiaomi-ble parser: 86.55 kg, 2021-04-11 16:19:01.
        val broadcast = MiScaleParser.parse(
            MiScaleParser.SERVICE_V1,
            bytes("22 9E 43 E5 07 04 0B 10 13 01"),
        )!!

        assertThat(broadcast.reading.grams).isEqualTo(86_550)
        assertThat(broadcast.stabilized).isTrue()
        assertThat(broadcast.weightRemoved).isFalse()
        // The original scale has no electrodes, so there is nothing to report.
        assertThat(broadcast.reading.impedanceOhms).isNull()
        assertThat(broadcast.reading.scaleClock)
            .isEqualTo(ScaleClock(2021, 4, 11, 16, 19, 1))
    }

    @Test
    fun `a weight still settling is not a reading`() {
        // The same frame with the stabilized bit cleared, which is what arrives many times a
        // second while someone is stepping on.
        val broadcast = MiScaleParser.parse(
            MiScaleParser.SERVICE_V2,
            bytes("02 06 B2 07 05 04 0F 02 01 AC 01 86 42"),
        )!!

        assertThat(broadcast.reading.grams).isEqualTo(85_150)
        assertThat(broadcast.stabilized).isFalse()
        assertThat(broadcast.isFinal).isFalse()
    }

    @Test
    fun `stepping off is not a reading either`() {
        val broadcast = MiScaleParser.parse(
            MiScaleParser.SERVICE_V2,
            bytes("02 A6 B2 07 05 04 0F 02 01 AC 01 86 42"),
        )!!

        assertThat(broadcast.stabilized).isTrue()
        assertThat(broadcast.weightRemoved).isTrue()
        assertThat(broadcast.isFinal).isFalse()
    }

    @Test
    fun `pounds and jin convert to the same grams as kilograms would`() {
        // 85.15 kg is 187.7 lb. The raw value is hundredths of a pound for both non-metric
        // units, and a jin is exactly half a kilogram.
        // 187.72 lb at hundredths is 18772, which is 0x4954.
        val inPounds = MiScaleParser.parse(
            MiScaleParser.SERVICE_V2,
            bytes("03 26 B2 07 05 04 0F 02 01 AC 01 54 49"),
        )!!
        assertThat(inPounds.reading.grams).isWithin(60).of(85_150)

        // 85.15 kg is 170.3 jin, raw 17030 at hundredths.
        val inJin = MiScaleParser.parse(
            MiScaleParser.SERVICE_V2,
            bytes("02 66 B2 07 05 04 0F 02 01 AC 01 86 42"),
        )!!
        assertThat(inJin.reading.grams).isEqualTo(85_150)
    }

    @Test
    fun `impedance is only reported once it has settled`() {
        // Impedance bit clear: the number in bytes 9 and 10 is not usable yet.
        val broadcast = MiScaleParser.parse(
            MiScaleParser.SERVICE_V2,
            bytes("02 24 B2 07 05 04 0F 02 01 AC 01 86 42"),
        )!!

        assertThat(broadcast.reading.impedanceOhms).isNull()
        // The weight is still good, so the reading is still worth recording.
        assertThat(broadcast.isFinal).isTrue()
    }

    @Test
    fun `a frame of the wrong length for its service is refused`() {
        // A version 1 payload arriving under the version 2 identifier is not a short version 2
        // frame: the weight is at the other end.
        assertThat(
            MiScaleParser.parse(MiScaleParser.SERVICE_V2, bytes("22 9E 43 E5 07 04 0B 10 13 01")),
        ).isNull()
        assertThat(MiScaleParser.parse(0x180F, bytes("22 9E 43 E5 07 04 0B 10 13 01"))).isNull()
        assertThat(MiScaleParser.handles(MiScaleParser.SERVICE_V1, ByteArray(10))).isTrue()
        assertThat(MiScaleParser.handles(MiScaleParser.SERVICE_V1, ByteArray(13))).isFalse()
    }
}
