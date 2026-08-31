package com.weighttrack.core.model

/**
 * Where a body-composition figure came from, and how much it is worth.
 *
 * A number on a screen with nothing beside it reads as a measurement. A bioelectrical impedance
 * scale is not measuring fat, it is estimating it from how easily a small current crosses the
 * body, and how it turns that into a percentage is the manufacturer's own arithmetic, unpublished
 * and different between makes. Saying which of these a figure is is the difference between
 * information and a claim the app cannot support.
 */
enum class CompositionQuality {
    /** The scale sent this figure. How it arrived at it is the manufacturer's own business. */
    REPORTED_BY_SCALE,

    /** WeightTrack worked it out here, from measurements somebody took with a tape. */
    ESTIMATED_BY_APP,

    /** Somebody typed it in. */
    ENTERED_BY_HAND,
}

/**
 * Everything a scale said about one weigh-in beyond the weight.
 *
 * Kept whole. The parsers and the scale screen have always carried muscle, lean mass, water,
 * impedance and basal metabolism, and saving kept the weight and the body-fat percentage and
 * silently dropped the rest: a person watching their muscle mass on the scale's own display had
 * no way to keep it, and nothing said it was being thrown away.
 *
 * Null means not measured, which is not zero. A plain scale sends a weight and nothing else, and
 * that capture is as valid as a full one.
 */
data class BodyComposition(
    val muscleMassGrams: Int? = null,
    val fatFreeMassGrams: Int? = null,
    val softLeanMassGrams: Int? = null,
    val bodyWaterMassGrams: Int? = null,
    val musclePercent: Double? = null,
    val impedanceOhms: Double? = null,
    val basalMetabolismKcal: Double? = null,
    /** What the scale worked out itself, which need not agree with what this app works out. */
    val scaleBmi: Double? = null,
    /**
     * The height the scale was told, when it reports one.
     *
     * A standards-compliant scale sends it beside the composition, and the parser has always
     * read it. It is not this app's idea of the person's height, which lives on their profile:
     * it is what the scale believes, and it is worth keeping because it is what every figure the
     * scale worked out was worked out from.
     */
    val heightMm: Int? = null,
    /** The slot a family scale filed it under, when it has slots. Not a profile in this app. */
    val scaleUserId: Int? = null,
    /** What the scale calls itself, as it advertised. */
    val device: String? = null,
    /** Which reader understood it: the standard service, or one vendor's format. */
    val protocol: String? = null,
    val quality: CompositionQuality = CompositionQuality.REPORTED_BY_SCALE,
) {
    /**
     * Whether anything beyond the weight was actually measured.
     *
     * The scale's own name is not measurement. A plain scale connected over Bluetooth reports a
     * weight and nothing else, and treating "it told us what it is called" as composition wrote
     * three columns and shipped them to every other device for a reading that has none.
     */
    val hasAnything: Boolean get() = muscleMassGrams != null || fatFreeMassGrams != null ||
        softLeanMassGrams != null || bodyWaterMassGrams != null || musclePercent != null ||
        impedanceOhms != null || basalMetabolismKcal != null || scaleBmi != null ||
        heightMm != null || scaleUserId != null

    /** Whether it is worth saving at all: a bare weight leaves nothing to record. */
    val isEmpty: Boolean get() = !hasAnything
}
