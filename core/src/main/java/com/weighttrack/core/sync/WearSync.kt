package com.weighttrack.core.sync

import com.weighttrack.core.model.WeightUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the phone and the watch say to each other.
 *
 * Both sides are built from this one file so a rename cannot leave the watch reading a key the
 * phone stopped writing. The payloads are JSON bytes rather than a DataMap, because a DataMap
 * would put the field names in two places.
 */
object WearSync {

    /** Phone to watch: the current figures, held as a data item so the watch keeps them offline. */
    const val PATH_SUMMARY = "/weighttrack/summary"

    /**
     * Watch to phone: one weight the person entered on the watch.
     *
     * A data item under this prefix rather than a message, because the Data Layer holds an item
     * until the other side has it. A weight logged on a walk with the phone at home still
     * arrives, which a message would not.
     */
    const val PATH_LOG_WEIGHT = "/weighttrack/log-weight"

    /** Watch to phone: "I am open, send me the current figures". */
    const val PATH_REQUEST_SUMMARY = "/weighttrack/request-summary"

    /** The capability the phone advertises, so the watch can tell whether it is installed. */
    const val CAPABILITY_PHONE = "weighttrack_phone"

    /** One reading gets its own item, so two logged before the phone reappears both survive. */
    fun logPath(clientRecordId: String): String = "$PATH_LOG_WEIGHT/$clientRecordId"

    fun isLogPath(path: String?): Boolean = path != null && path.startsWith("$PATH_LOG_WEIGHT/")

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(summary: WearSummary): ByteArray = json.encodeToString(summary).encodeToByteArray()

    fun decodeSummary(bytes: ByteArray?): WearSummary? = decode(bytes)

    fun encode(log: WearWeightLog): ByteArray = json.encodeToString(log).encodeToByteArray()

    fun decodeWeightLog(bytes: ByteArray?): WearWeightLog? = decode(bytes)

    private inline fun <reified T> decode(bytes: ByteArray?): T? {
        // A payload from an older or newer build is ignored rather than crashing the listener:
        // the watch and the phone are updated separately and will not always match.
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching { json.decodeFromString<T>(bytes.decodeToString()) }.getOrNull()
    }
}

/**
 * Everything the watch shows, in grams, exactly as the phone worked it out.
 *
 * No formatted strings: the watch has the same unit maths, and sending text would stop the
 * rotary picker from stepping in the person's own unit.
 */
@Serializable
data class WearSummary(
    val trendGrams: Int? = null,
    val latestGrams: Int? = null,
    /** Change over the last seven days, signed, in grams. Null when there is not enough history. */
    val weekChangeGrams: Double? = null,
    val goalGrams: Int? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** The day of the most recent reading, as an epoch day, so the watch can say how stale it is. */
    val lastLoggedEpochDay: Long? = null,
    val entryCount: Int = 0,
    /**
     * True when the app lock is on.
     *
     * The watch then shows nothing readable, for the same reason the home screen widget does
     * not: a weight on a wrist is exactly what the lock exists to keep off a glanceable surface.
     */
    val hidden: Boolean = false,
) {
    /** The number to open the picker on: the trend if there is one, else the last reading. */
    val startingGrams: Int? get() = trendGrams ?: latestGrams

    val hasData: Boolean get() = !hidden && entryCount > 0
}

/** One weight entered on the watch. */
@Serializable
data class WearWeightLog(
    val grams: Int,
    val timestampUtcMillis: Long,
    /**
     * Made on the watch, so a message delivered twice does not become two readings.
     *
     * The Data Layer redelivers when the phone was unreachable at the time.
     */
    val clientRecordId: String,
)
