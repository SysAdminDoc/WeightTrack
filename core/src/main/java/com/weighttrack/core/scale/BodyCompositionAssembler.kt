package com.weighttrack.core.scale

/** A reading, and whether it is a better version of one already reported for this weigh-in. */
data class AssembledReading(
    val reading: ScaleReading,
    /**
     * True when this replaces the last reading handed out rather than adding to it.
     *
     * A scale that sends the weight first and the body composition a moment later produces two
     * of these for one time on the scale. Reporting them as two readings files the weight twice
     * and throws the composition away, so the second one says it supersedes the first.
     */
    val revisesPrevious: Boolean,
)

/**
 * Puts a scale's indications back together into whole readings.
 *
 * Three things make this necessary. A body composition measurement too big for one indication is
 * split across two, and the second half looks exactly like a complete small measurement: same
 * mandatory fields, but with the timestamp and user bits cleared and only the leftover fields
 * flagged. Weight is optional on the composition characteristic, so a scale is free to send the
 * composition there and the weight on the plain weight characteristic, in either order. And a
 * weight that arrives first has to be reported straight away, because on a weight-only scale
 * nothing else is coming.
 *
 * Time is passed in rather than read, so a weight left over from an earlier weigh-in on the same
 * connection cannot be attached to a later one, and so this stays testable.
 *
 * Not thread safe, and not meant to be: one of these belongs to one connection.
 */
class BodyCompositionAssembler {

    private var pendingSplit: BodyCompositionPacket? = null
    private var pendingComposition: BodyCompositionPacket? = null
    private var lastWeight: ScaleReading? = null
    private var lastWeightAtMillis: Long = 0

    /** A weight arrived on characteristic 0x2A9D. */
    fun onWeightMeasurement(reading: ScaleReading, atMillis: Long): List<AssembledReading> {
        // A composition already waiting for a weight is this one's other half.
        pendingComposition?.let { waiting ->
            pendingComposition = null
            lastWeight = null
            return listOf(
                AssembledReading(
                    reading = waiting.reading.copy(grams = reading.grams),
                    revisesPrevious = false,
                ),
            )
        }

        lastWeight = reading
        lastWeightAtMillis = atMillis
        // A split composition still waiting for its continuation is left alone: this weight is
        // the one it is missing, and destroying it here would lose the continuation's fields.
        return listOf(AssembledReading(reading, revisesPrevious = false))
    }

    /** A composition packet arrived on characteristic 0x2A9C. */
    fun onBodyComposition(packet: BodyCompositionPacket, atMillis: Long): List<AssembledReading> {
        val waiting = pendingSplit
        val whole = if (waiting != null) {
            pendingSplit = null
            // Whether or not this really is the continuation, the halves belong together: the
            // second carries the fields that did not fit and nothing that identifies itself.
            combine(waiting, packet)
        } else if (packet.isSplit) {
            pendingSplit = packet
            return emptyList()
        } else {
            packet
        }

        return finish(whole, atMillis)
    }

    /** The connection dropped. Anything half-assembled is reported rather than thrown away. */
    fun flush(atMillis: Long): List<AssembledReading> {
        val waiting = pendingSplit
        pendingSplit = null
        pendingComposition = null
        val out = waiting?.let { finish(it, atMillis) }.orEmpty()
        // A weight from this session must not be carried into the next one.
        lastWeight = null
        return out
    }

    private fun finish(packet: BodyCompositionPacket, atMillis: Long): List<AssembledReading> {
        val carried = lastWeight?.takeIf { atMillis - lastWeightAtMillis <= WEIGH_IN_WINDOW_MILLIS }
        val grams = if (packet.hasWeight) packet.reading.grams else carried?.grams

        if (grams == null || grams <= 0) {
            // Nothing on a reading means anything without a weight, so this waits for one rather
            // than being dropped: the scale may still send it on the other characteristic.
            pendingComposition = packet
            return emptyList()
        }

        // A weight already handed out for this weigh-in is being improved on, not added to.
        val revises = carried != null
        lastWeight = null
        pendingComposition = null
        return listOf(
            AssembledReading(
                reading = packet.reading.copy(grams = grams),
                revisesPrevious = revises,
            ),
        )
    }

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

    companion object {
        /**
         * How long a weight stays available to a composition that arrives without one.
         *
         * A scale that keeps the connection open between weigh-ins would otherwise attach this
         * morning's weight to this evening's body fat.
         */
        const val WEIGH_IN_WINDOW_MILLIS = 30_000L
    }
}
