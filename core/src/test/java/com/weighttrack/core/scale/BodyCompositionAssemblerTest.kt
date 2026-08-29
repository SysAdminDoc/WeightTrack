package com.weighttrack.core.scale

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BodyCompositionAssemblerTest {

    private val assembler = BodyCompositionAssembler()

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
        )

        assertThat(out).hasSize(1)
        assertThat(out.single().grams).isEqualTo(82_500)
        assertThat(out.single().bodyFatPercent!!).isWithin(1e-9).of(21.2)
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
        )
        assertThat(first).isEmpty()

        val second = assembler.onBodyComposition(
            packet(isSplit = true, bodyFatPercent = 21.2, muscleMassGrams = 35_000, impedanceOhms = 500.0),
        )

        assertThat(second).hasSize(1)
        val reading = second.single()
        assertThat(reading.grams).isEqualTo(82_500)
        assertThat(reading.muscleMassGrams).isEqualTo(35_000)
        assertThat(reading.impedanceOhms!!).isWithin(1e-9).of(500.0)
        // The identifying fields come from the first half, which is the only one that has them.
        assertThat(reading.scaleUserId).isEqualTo(2)
        assertThat(reading.scaleClock).isEqualTo(ScaleClock(2026, 8, 29, 7, 30, 15))
    }

    @Test
    fun `a composition with no weight waits for the weight characteristic`() {
        // Weight is optional on the composition characteristic, so a scale is free to send the
        // composition there and the weight on 0x2A9D.
        val held = assembler.onBodyComposition(
            packet(isSplit = true, bodyFatPercent = 21.2, muscleMassGrams = 35_000),
        )
        assertThat(held).isEmpty()

        val out = assembler.onWeightMeasurement(ScaleReading(grams = 82_500))

        assertThat(out).hasSize(1)
        assertThat(out.single().grams).isEqualTo(82_500)
        assertThat(out.single().muscleMassGrams).isEqualTo(35_000)
    }

    @Test
    fun `a weight on its own is a reading`() {
        val out = assembler.onWeightMeasurement(ScaleReading(grams = 82_500))

        assertThat(out).hasSize(1)
        assertThat(out.single().grams).isEqualTo(82_500)
    }

    @Test
    fun `a weight sent before its composition is carried forward, not filed twice`() {
        val fromWeight = assembler.onWeightMeasurement(ScaleReading(grams = 82_500))
        assertThat(fromWeight).hasSize(1)

        val fromComposition = assembler.onBodyComposition(
            packet(bodyFatPercent = 21.2, muscleMassGrams = 35_000),
        )

        assertThat(fromComposition).hasSize(1)
        assertThat(fromComposition.single().grams).isEqualTo(82_500)
        assertThat(fromComposition.single().bodyFatPercent!!).isWithin(1e-9).of(21.2)
    }

    @Test
    fun `a composition with no weight anywhere is not a reading`() {
        // Everything on a reading hangs off a weight, so there is nothing to store.
        val out = assembler.onBodyComposition(packet(bodyFatPercent = 21.2))

        assertThat(out).isEmpty()
    }

    @Test
    fun `a half assembled measurement is reported when the connection drops`() {
        assembler.onBodyComposition(
            packet(grams = 82_500, hasWeight = true, isSplit = true, bodyFatPercent = 21.2),
        )

        val out = assembler.flush()

        assertThat(out).hasSize(1)
        assertThat(out.single().grams).isEqualTo(82_500)
        // Flushing twice must not produce the reading again.
        assertThat(assembler.flush()).isEmpty()
    }
}
