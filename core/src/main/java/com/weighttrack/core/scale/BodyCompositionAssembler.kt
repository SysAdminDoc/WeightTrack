package com.weighttrack.core.scale

/**
 * Puts a scale's indications back together into whole readings.
 *
 * Two things make this necessary. A body composition measurement too big for one indication is
 * split across two, and the second half looks exactly like a complete small measurement: same
 * mandatory fields, but with the timestamp and user bits cleared and only the leftover fields
 * flagged. A parser that takes it at face value files a second, partial reading.
 *
 * The other is that weight is optional on the composition characteristic, so a scale is free to
 * send the composition there and the weight on the plain weight characteristic. Neither half is
 * a usable reading alone.
 *
 * Not thread safe, and not meant to be: one of these belongs to one connection.
 */
class BodyCompositionAssembler {

    private var pendingSplit: BodyCompositionPacket? = null
    private var pendingWeightGrams: Int? = null

    /** A weight arrived on characteristic 0x2A9D. */
    fun onWeightMeasurement(reading: ScaleReading): List<ScaleReading> {
        val waiting = pendingSplit
        if (waiting != null && !waiting.hasWeight) {
            // The composition came first and had no weight of its own.
            pendingSplit = null
            pendingWeightGrams = null
            return listOf(merge(waiting.reading, reading.grams))
        }
        pendingWeightGrams = reading.grams
        return listOf(reading)
    }

    /** A composition packet arrived on characteristic 0x2A9C. */
    fun onBodyComposition(packet: BodyCompositionPacket): List<ScaleReading> {
        val waiting = pendingSplit
        if (waiting != null) {
            pendingSplit = null
            // Whether or not this really is the continuation, the halves belong together: the
            // second carries the fields that did not fit and nothing that identifies itself.
            return finish(combine(waiting, packet))
        }
        if (packet.isSplit) {
            pendingSplit = packet
            return emptyList()
        }
        return finish(packet)
    }

    /** The connection dropped. Anything half-assembled is reported rather than thrown away. */
    fun flush(): List<ScaleReading> {
        val waiting = pendingSplit ?: return emptyList()
        pendingSplit = null
        return finish(waiting)
    }

    private fun finish(packet: BodyCompositionPacket): List<ScaleReading> {
        val weight = if (packet.hasWeight) packet.reading.grams else pendingWeightGrams
        pendingWeightGrams = null
        // A composition with no weight anywhere is not a reading this app can store: everything
        // here hangs off a weight.
        if (weight == null || weight <= 0) return emptyList()
        return listOf(merge(packet.reading, weight))
    }

    private fun merge(reading: ScaleReading, grams: Int): ScaleReading = reading.copy(grams = grams)

    /** The first packet keeps the identifying fields; the second only adds what it carries. */
    private fun combine(
        first: BodyCompositionPacket,
        second: BodyCompositionPacket,
    ): BodyCompositionPacket = BodyCompositionPacket(
        reading = ScaleReading(
            grams = if (first.hasWeight) first.reading.grams else second.reading.grams,
            bodyFatPercent = first.reading.bodyFatPercent ?: second.reading.bodyFatPercent,
            muscleMassGrams = first.reading.muscleMassGrams ?: second.reading.muscleMassGrams,
            fatFreeMassGrams = first.reading.fatFreeMassGrams ?: second.reading.fatFreeMassGrams,
            softLeanMassGrams = first.reading.softLeanMassGrams ?: second.reading.softLeanMassGrams,
            bodyWaterMassGrams = first.reading.bodyWaterMassGrams ?: second.reading.bodyWaterMassGrams,
            musclePercent = first.reading.musclePercent ?: second.reading.musclePercent,
            impedanceOhms = first.reading.impedanceOhms ?: second.reading.impedanceOhms,
            basalMetabolismKcal = first.reading.basalMetabolismKcal ?: second.reading.basalMetabolismKcal,
            heightMm = first.reading.heightMm ?: second.reading.heightMm,
            bmi = first.reading.bmi ?: second.reading.bmi,
            // Only the first packet carries these; the continuation clears their bits.
            scaleUserId = first.reading.scaleUserId,
            scaleClock = first.reading.scaleClock,
        ),
        hasWeight = first.hasWeight || second.hasWeight,
        isSplit = false,
    )
}
