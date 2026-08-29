package com.weighttrack.core.scale

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VendorScaleProtocolTest {

    private val now = 1_622_289_692_000L

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02x", it.toInt() and 0xFF) }

    // ---- Beurer and Sanitas -------------------------------------------------------------

    @Test
    fun `the beurer command byte carries the family in its high nibble`() {
        // Getting this wrong is silent: the scale simply never answers.
        val bf700 = BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF700)
        val bf710 = BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF710)

        assertThat(hex(bf700.onConnected(now).first())).isEqualTo("f6 01")
        assertThat(hex(bf710.onConnected(now).first())).isEqualTo("e6 01")
        // Set time is the same command with a different family nibble, then the seconds.
        assertThat(hex(bf700.onConnected(now)[1])).startsWith("f9 ")
        assertThat(hex(bf710.onConnected(now)[1])).startsWith("e9 ")
    }

    @Test
    fun `a beurer live weight is fifty grams a unit and only settles once`() {
        val scale = BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF700)

        // 82.5 kg is 1650 units of fifty grams. Byte two non-zero means it is still moving.
        val moving = scale.onNotification(bytes(0xF7, 0x58, 0x01, 0x06, 0x72), now)
        assertThat(moving.liveGrams).isEqualTo(82_500)
        assertThat(moving.readings).isEmpty()

        val settled = scale.onNotification(bytes(0xF7, 0x58, 0x00, 0x06, 0x72), now)
        assertThat(settled.readings.single().grams).isEqualTo(82_500)
        assertThat(settled.liveGrams).isNull()
    }

    @Test
    fun `a beurer measurement is put back together from two notifications`() {
        val scale = BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF710)

        // The first notification is a header carrying the user, not measurement data.
        val first = scale.onNotification(
            bytes(0xE7, 0x59, 0x02, 0x01, 0x60, 0xB2, 0x2D, 0x1C, 0x06, 0x72, 0x01, 0xF4),
            now,
        )
        assertThat(first.readings).isEmpty()
        // Every part has to be acknowledged or the scale stops talking.
        assertThat(hex(first.writes.single())).isEqualTo("e7 f1 59 02 01")

        val second = scale.onNotification(
            bytes(0xE7, 0x59, 0x02, 0x02, 0x00, 0xD4, 0x01, 0xF4, 0x02, 0x30, 0x0F, 0xA0),
            now,
        )

        val reading = second.readings.single()
        // Bytes four and five of the joined payload, in fifty gram units.
        assertThat(reading.grams).isEqualTo(0x0672 * 50)
        assertThat(reading.impedanceOhms!!).isWithin(1e-9).of(500.0)
        assertThat(reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
    }

    @Test
    fun `a beurer measurement payload reads every field in order`() {
        val payload = bytes(
            0x60, 0xB2, 0x2D, 0x1C, // timestamp
            0x06, 0x72, // weight, 1650 units of 50 g
            0x01, 0xF4, // impedance, 500 ohms
            0x00, 0xD4, // body fat, 21.2 percent
            0x01, 0xF4, // water, 50.0 percent
            0x01, 0x90, // muscle, 40.0 percent
            0x00, 0x50, // bone mass
            0x06, 0x40, // basal metabolism
            0x08, 0x00, // active metabolism
            0x00, 0xF1, // body mass index, 24.1
        )

        val reading = BeurerSanitasProtocol.parseMeasurement(payload)!!

        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.impedanceOhms!!).isWithin(1e-9).of(500.0)
        assertThat(reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
        assertThat(reading.musclePercent!!).isWithin(1e-9).of(40.0)
        // The percentages are turned into masses against the weight in the same frame.
        assertThat(reading.bodyWaterMassGrams).isEqualTo(41_250)
        assertThat(reading.muscleMassGrams).isEqualTo(33_000)
        assertThat(reading.basalMetabolismKcal!!).isWithin(1e-9).of(1_600.0)
        assertThat(reading.bmi!!).isWithin(1e-9).of(24.1)
    }

    @Test
    fun `a beurer payload with no weight is not a measurement`() {
        assertThat(BeurerSanitasProtocol.parseMeasurement(ByteArray(22))).isNull()
        assertThat(BeurerSanitasProtocol.parseMeasurement(ByteArray(3))).isNull()
    }

    // ---- eufy ---------------------------------------------------------------------------

    @Test
    fun `the one by one setup command ends with an exclusive or, not a sum`() {
        val command = EufyProtocols.OneByOne().onConnected(now).single()

        assertThat(command).hasLength(11)
        val expected = command.take(10).fold(0) { total, byte -> total xor (byte.toInt() and 0xFF) }
        assertThat(command.last().toInt() and 0xFF).isEqualTo(expected)
        assertThat(hex(command)).startsWith("fd 37 00 01")
    }

    @Test
    fun `a one by one measurement is hundredths of a kilogram, little endian`() {
        val scale = EufyProtocols.OneByOne()

        // 82.50 kg is 8250, which is 0x203A, sent low byte first at offsets three and four.
        val settling = scale.onNotification(
            bytes(0xCF, 0x00, 0x00, 0x3A, 0x20, 0, 0, 0, 0, 0x05, 0),
            now,
        )
        assertThat(settling.liveGrams).isEqualTo(82_500)
        assertThat(settling.readings).isEmpty()

        val done = scale.onNotification(
            bytes(0xCF, 0x00, 0x00, 0x3A, 0x20, 0, 0, 0, 0, 0x00, 0),
            now,
        )
        assertThat(done.readings.single().grams).isEqualTo(82_500)

        // One means the reading finished with no impedance to report, which is still a weight.
        val withoutImpedance = scale.onNotification(
            bytes(0xCF, 0x00, 0x00, 0x3A, 0x20, 0, 0, 0, 0, 0x01, 0),
            now,
        )
        assertThat(withoutImpedance.readings.single().grams).isEqualTo(82_500)
    }

    @Test
    fun `a frame that is not a measurement is ignored`() {
        val scale = EufyProtocols.OneByOne()
        assertThat(scale.onNotification(bytes(0xF1, 0x00), now).readings).isEmpty()
        assertThat(scale.onNotification(ByteArray(0), now).readings).isEmpty()
    }

    @Test
    fun `the bodysense checksum matches the published worked example`() {
        // f2 2b 30 b2 60 cc sums to 0x32b, and the low byte is what goes on the end.
        val body = bytes(0xF2, 0x2B, 0x30, 0xB2, 0x60, 0xCC)

        assertThat(EufyProtocols.BodySense.checksum(body).toInt() and 0xFF).isEqualTo(0x2B)
    }

    @Test
    fun `the bodysense time sync is little endian behind the AC 02 prefix`() {
        // The published write-up calls this big endian and then works an example that is not.
        val command = EufyProtocols.BodySense.timeSync(1_622_289_692_000L)

        assertThat(hex(command)).startsWith("ac 02 f2 1c 2d b2 60 cc")
        val body = command.copyOfRange(2, command.size - 1)
        assertThat(command.last()).isEqualTo(EufyProtocols.BodySense.checksum(body))
    }

    @Test
    fun `a bodysense weight is tenths of a kilogram, big endian`() {
        val scale = EufyProtocols.BodySense()

        // 82.5 kg is 825 tenths, which is 0x0339. CA at offset six means it has settled.
        val settled = scale.onNotification(bytes(0xAC, 0x02, 0x03, 0x39, 0, 0, 0xCA, 0), now)
        assertThat(settled.readings.single().grams).isEqualTo(82_500)

        val live = scale.onNotification(bytes(0xAC, 0x02, 0x03, 0x39, 0, 0, 0xCE, 0), now)
        assertThat(live.liveGrams).isEqualTo(82_500)
        assertThat(live.readings).isEmpty()
    }

    // ---- Renpho and QN ------------------------------------------------------------------

    @Test
    fun `the qn scale is not written to until it has introduced itself`() {
        val scale = QnScaleProtocol()

        assertThat(scale.onConnected(now)).isEmpty()
    }

    @Test
    fun `the qn hello sets the protocol type and the resolution`() {
        val scale = QnScaleProtocol()

        val step = scale.onNotification(
            bytes(0x12, 0x00, 0x2A, 0, 0, 0, 0, 0, 0, 0, 0x01),
            now,
        )

        assertThat(step.writes).hasSize(2)
        // The type the scale announced is echoed back in the configuration frame.
        assertThat(hex(step.writes[0])).startsWith("13 09 2a 01 10")
        val config = step.writes[0]
        val sum = config.dropLast(1).fold(0) { total, byte -> total + (byte.toInt() and 0xFF) }
        assertThat(config.last().toInt() and 0xFF).isEqualTo(sum and 0xFF)
        // Byte ten said hundredths, so a later weight is read at that resolution.
        val weight = scale.onNotification(bytes(0x10, 0, 0, 0x20, 0x3A, 0x01, 0, 0), now)
        assertThat(weight.readings.single().grams).isEqualTo(82_500)
    }

    @Test
    fun `the qn clock counts from midnight utc at the start of 2000`() {
        // Not the value openScale uses, which is five hours later.
        assertThat(QnScaleProtocol.EPOCH_2000_SECONDS).isEqualTo(946_684_800L)

        val scale = QnScaleProtocol()
        val setTime = scale.onNotification(bytes(0x12, 0, 0x2A, 0, 0, 0, 0, 0, 0, 0, 1), now)
            .writes[1]

        assertThat(setTime.first().toInt()).isEqualTo(0x02)
        val seconds = setTime.drop(1)
            .mapIndexed { index, byte -> (byte.toLong() and 0xFF) shl (index * 8) }
            .sum()
        assertThat(seconds).isEqualTo(now / 1000L - 946_684_800L)
    }

    @Test
    fun `a qn weight is only a reading once the scale says it settled`() {
        val scale = QnScaleProtocol()
        scale.onNotification(bytes(0x12, 0, 0x2A, 0, 0, 0, 0, 0, 0, 0, 1), now)

        val moving = scale.onNotification(bytes(0x10, 0, 0, 0x20, 0x3A, 0x00, 0x01, 0xF4), now)
        assertThat(moving.liveGrams).isEqualTo(82_500)
        assertThat(moving.readings).isEmpty()

        val settled = scale.onNotification(bytes(0x10, 0, 0, 0x20, 0x3A, 0x01, 0x01, 0xF4), now)
        assertThat(settled.readings.single().grams).isEqualTo(82_500)
        assertThat(settled.readings.single().impedanceOhms!!).isWithin(1e-9).of(500.0)
    }

    @Test
    fun `a qn weight the resolution cannot explain is refused, not rescaled`() {
        val scale = QnScaleProtocol()
        // Tenths of a kilogram, so 0x203A is 825.0 kg. The published habit is to divide again
        // until the number looks plausible; a silently rescaled reading is worse than none.
        scale.onNotification(bytes(0x12, 0, 0x2A, 0, 0, 0, 0, 0, 0, 0, 0), now)

        val step = scale.onNotification(bytes(0x10, 0, 0, 0x20, 0x3A, 0x01, 0, 0), now)

        assertThat(step.readings).isEmpty()
        assertThat(step.liveGrams).isNull()
    }

    @Test
    fun `every vendor recognises its own name and not the others`() {
        val beurer = BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF700)
        val eufy = EufyProtocols.OneByOne()
        val qn = QnScaleProtocol()

        assertThat(beurer.handles("BF700")).isTrue()
        assertThat(beurer.handles("QN-Scale")).isFalse()
        assertThat(eufy.handles("eufy T9147")).isTrue()
        assertThat(eufy.handles("BF700")).isFalse()
        assertThat(qn.handles("QN-Scale")).isTrue()
        assertThat(qn.handles("Renpho-Scale")).isTrue()
        assertThat(qn.handles("eufy")).isFalse()
        assertThat(qn.handles(null)).isFalse()
    }
}
