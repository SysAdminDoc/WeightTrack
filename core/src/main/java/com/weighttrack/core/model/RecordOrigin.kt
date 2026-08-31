package com.weighttrack.core.model

/**
 * Which app, and which piece of hardware, a reading came from.
 *
 * Health Connect is a shared pool. A weigh-in in it was written by somebody: a scale's own app, a
 * watch, a fitness tracker, another logger. Without that on the row there is no way to answer the
 * two questions people actually ask when a number looks wrong, which are "where did this come
 * from" and "stop taking these". Both used to be unanswerable, and a reading imported twice
 * through two different apps looked like two weigh-ins a minute apart.
 *
 * [packageName] is the app. [device] is the make and model when the writer said, which plenty do
 * not.
 */
data class RecordOrigin(
    val packageName: String,
    val device: String? = null,
) {
    /** Whether this says anything at all. A blank package name is not an origin. */
    val isKnown: Boolean get() = packageName.isNotBlank()

    companion object {
        /** Reads one back, or answers null when nothing was stored. */
        fun of(packageName: String?, device: String?): RecordOrigin? =
            packageName?.takeIf { it.isNotBlank() }?.let { RecordOrigin(it, device?.ifBlank { null }) }
    }
}

/**
 * Which way readings are allowed to move between this app and Health Connect.
 *
 * One switch used to cover both directions, and connecting at all meant granting read and write
 * on every record type together. Somebody who wants their scale's history in here without this
 * app writing anything back had no way to say so, and neither did somebody who wants the reverse.
 * The permission request is built from this, so a direction that is never used is never asked for.
 */
enum class HealthDirection {
    /** Read what other apps wrote and publish what this one records. */
    TWO_WAY,

    /** Take readings in and write nothing back. */
    READ_ONLY,

    /** Publish this app's readings and import nothing. */
    WRITE_ONLY,
    ;

    val reads: Boolean get() = this != WRITE_ONLY
    val writes: Boolean get() = this != READ_ONLY
}
