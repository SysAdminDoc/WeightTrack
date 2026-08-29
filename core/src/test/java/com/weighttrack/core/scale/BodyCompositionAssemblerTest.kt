package com.weighttrack.core.scale

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BodyCompositionAssemblerTest {

    private val assembler = BodyCompositionAssembler()

    /** Any instant will do unless a test is about the window between weigh-ins. */
    private val now = 1_000L

    private fun packet(
        grams: Int = 0,
        hasWeight: Boolean = false,
        isSplit: Boolean = false,
        bodyFatPercent: Double? = null,
        muscleMassGrams: Int? = null,
        impedanceOhms: Double? = null,
        scaleUserId: Int? = null,
        scaleClock: ScaleClock? = null,
    ) = BodyCompositionPacket(
        reading = ScaleReading(
            grams = grams,
            bodyFatPercent = bodyFatPercent,
            muscleMassGrams = muscleMassGrams,
            impedanceOhms = impedanceOhms,
            scaleUserId = scaleUserId,
            scaleClock = scaleClock,
        ),
        hasWeight = hasWeight,
        isSplit = isSplit,
    )

    @Test
    fun `a whole measurement in one packet comes straight out`() {
        val out = assembler.onBodyComposition(
            packet(grams = 82_500, hasWeight = true, bodyFatPercent = 21.2),
            now,
        )

        assertThat(out).hasSize(1)
        assertThat(out.single().reading.grams).isEqualTo(82_500)
        assertThat(out.single().reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
        assertThat(out.single().revisesPrevious).isFalse()
    }

    @Test
    fun `a split measurement is held until its other half arrives`() {
        // The continuation looks exactly like a complete small measurement: it repeats the
        // mandatory fields and clears the timestamp and user bits. Taken at face value it files
        // a second, partial reading with no timestamp.
        val first = assembler.onBodyComposition(
            packet(
                grams = 82_500,
                hasWeight = true,
                isSplit = true,
                bodyFatPercent = 21.2,
                scaleUserId = 2,
                scaleClock = ScaleClock(2026, 8, 29, 7, 30, 15),
            ),
            now,
        )
        assertThat(first).isEmpty()

        val second = assembler.onBodyComposition(
            packet(isSplit = true, bodyFatPercent = 21.2, muscleMassGrams = 35_000, impedanceOhms = 500.0),
            now,
        )

        assertThat(second).hasSize(1)
        val reading = second.single().reading
        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.muscleMassGrams).isEqualTo(35_000)
        assertThat(reading.impedanceOhms!!).isWithin(1e-9).of(500.0)
        // The identifying fields come from the first half, which is the only one that has them.
        assertThat(reading.scaleUserId).isEqualTo(2)
        assertThat(reading.scaleClock).isEqualTo(ScaleClock(2026, 8, 29, 7, 30, 15))
    }

    @Test
    fun `a weight arriving mid split does not destroy the half already held`() {
        assembler.onBodyComposition(
            packet(isSplit = true, bodyFatPercent = 21.2, scaleUserId = 2),
            now,
        )

        // The scale sends the weight on the other characteristic between the two halves.
        val fromWeight = assembler.onWeightMeasurement(ScaleReading(grams = 82_500), now)
        assertThat(fromWeight).hasSize(1)

        val finished = assembler.onBodyComposition(
            packet(isSplit = true, muscleMassGrams = 35_000),
            now,
        )

        assertThat(finished).hasSize(1)
        val reading = finished.single().reading
        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
        assertThat(reading.muscleMassGrams).isEqualTo(35_000)
        assertThat(reading.scaleUserId).isEqualTo(2)
        // It replaces the bare weight already reported rather than adding a second reading.
        assertThat(finished.single().revisesPrevious).isTrue()
    }

    @Test
    fun `a composition with no weight waits for the weight characteristic`() {
        // Weight is optional on the composition characteristic, so a scale is free to send the
        // composition there and the weight on 0x2A9D. This one is not split, which used to mean
        // it was silently dropped.
        val held = assembler.onBodyComposition(
            packet(bodyFatPercent = 21.2, muscleMassGrams = 35_000),
            now,
        )
        assertThat(held).isEmpty()

        val out = assembler.onWeightMeasurement(ScaleReading(grams = 82_500), now)

        assertThat(out).hasSize(1)
        assertThat(out.single().reading.grams).isEqualTo(82_500)
        assertThat(out.single().reading.muscleMassGrams).isEqualTo(35_000)
        // Nothing was reported before it, so there is nothing to revise.
        assertThat(out.single().revisesPrevious).isFalse()
    }

    @Test
    fun `a weight on its own is a reading`() {
        val out = assembler.onWeightMeasurement(ScaleReading(grams = 82_500), now)

        assertThat(out).hasSize(1)
        assertThat(out.single().reading.grams).isEqualTo(82_500)
        assertThat(out.single().revisesPrevious).isFalse()
    }

    @Test
    fun `a composition after a weight revises it rather than filing a second reading`() {
        // A weight-only scale sends nothing else, so the weight has to be reported at once. A
        // body composition scale then sends the rest a moment later, and that is one weigh-in,
        // not two.
        val fromWeight = assembler.onWeightMeasurement(ScaleReading(grams = 82_500), now)
        assertThat(fromWeight).hasSize(1)
        assertThat(fromWeight.single().revisesPrevious).isFalse()

        val fromComposition = assembler.onBodyComposition(
            packet(bodyFatPercent = 21.2, muscleMassGrams = 35_000),
            now,
        )

        assertThat(fromComposition).hasSize(1)
        assertThat(fromComposition.single().revisesPrevious).isTrue()
        assertThat(fromComposition.single().reading.grams).isEqualTo(82_500)
        assertThat(fromComposition.single().reading.bodyFatPercent!!).isWithin(1e-9).of(21.2)
    }

    @Test
    fun `this morning's weight is not attached to this evening's body fat`() {
        // A scale that holds the connection open between weigh-ins would otherwise carry the
        // earlier weight forward forever.
        assembler.onWeightMeasurement(ScaleReading(grams = 82_500), atMillis = 0)

        val later = assembler.onBodyComposition(
            packet(bodyFatPercent = 21.2),
            atMillis = BodyCompositionAssembler.WEIGH_IN_WINDOW_MILLIS + 1,
        )

        assertThat(later).isEmpty()
    }

    @Test
    fun `a half assembled measurement is reported when the connection drops`() {
        assembler.onBodyComposition(
            packet(grams = 82_500, hasWeight = true, isSplit = true, bodyFatPercent = 21.2),
            now,
        )

        val out = assembler.flush(now)

        assertThat(out).hasSize(1)
        assertThat(out.single().reading.grams).isEqualTo(82_500)
        // Flushing twice must not produce the reading again.
        assertThat(assembler.flush(now)).isEmpty()
    }

    @Test
    fun `a weight does not survive the connection it arrived on`() {
        assembler.onWeightMeasurement(ScaleReading(grams = 82_500), now)
        assembler.flush(now)

        val out = assembler.onBodyComposition(packet(bodyFatPercent = 21.2), now)

        assertThat(out).isEmpty()
    }
}
