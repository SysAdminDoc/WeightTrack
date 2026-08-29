package com.weighttrack.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sync without an account.
 *
 * Each device writes one file and reads everybody else's. Nothing on a server decides anything,
 * there is nothing to sign up for, and the files can travel by whatever the person already uses:
 * a Syncthing folder, a Nextcloud directory, a memory stick.
 *
 * One file per device is the whole trick. Two devices editing one shared file is how folder sync
 * produces conflict copies, and once there are two files claiming to be the same thing a person
 * has to arbitrate by hand. Here nobody ever writes to anybody else's file, so there is nothing
 * for the sync tool to fall out over, and the merging happens in the app where it can be done
 * properly.
 *
 * Every record carries a stable identity that does not change between devices, and the time it
 * was last touched. Newest wins. That relies on the devices roughly agreeing about the time, so
 * a phone with a badly wrong clock can hold a stale edit in place: an honest limitation of doing
 * this without a server, and one worth knowing about rather than papering over.
 */
@Serializable
data class SyncDocument(
    val app: String = APP,
    val formatVersion: Int = FORMAT_VERSION,
    /**
     * Which device wrote this file.
     *
     * Used to break ties when two edits carry the same millisecond, so every device works out
     * the same answer rather than each preferring its own.
     */
    val deviceId: String,
    val writtenAtUtcMillis: Long,
    val profiles: List<SyncProfile> = emptyList(),
    val weights: List<SyncWeight> = emptyList(),
    val measurements: List<SyncMeasurement> = emptyList(),
    val water: List<SyncWater> = emptyList(),
    val fasts: List<SyncFast> = emptyList(),
    val goals: List<SyncGoal> = emptyList(),
    val macroTargets: List<SyncMacroTarget> = emptyList(),
    val settings: SyncSettings? = null,
    /**
     * What has been deleted, and when.
     *
     * Without these a delete never travels: the other device still holds the row, sees no reason
     * to drop it, and hands it straight back. A record that comes home every time it is deleted
     * is the single most irritating way for sync to be wrong.
     */
    val deletions: List<SyncDeletion> = emptyList(),
) {
    companion object {
        const val APP = "WeightTrack"
        const val FORMAT_VERSION = 1

        /** What a device's own file is called in a shared folder. */
        fun fileName(deviceId: String): String = "weighttrack-$deviceId.json"

        /** Whether a file in the folder is one of ours, and which device wrote it. */
        fun deviceIdOf(fileName: String): String? {
            if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null
            val id = fileName.substring(PREFIX.length, fileName.length - SUFFIX.length)
            // A folder sync tool that does fall out over something leaves files like
            // "weighttrack-abc.sync-conflict-20260101-120000-XYZ.json" behind. Reading one would
            // resurrect whatever it held, so anything that is not a plain identifier is skipped.
            return id.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
                ?.takeIf { !it.contains("sync-conflict") }
        }

        private const val PREFIX = "weighttrack-"
        private const val SUFFIX = ".json"

        val json = Json {
            prettyPrint = true
            // A file written by a newer version has to stay readable by this one, or an upgrade
            // on one device stops the other syncing at all.
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(document: SyncDocument): String =
            json.encodeToString(serializer(), document)

        /**
         * Reads a file, or returns null if it is not one of ours.
         *
         * Anything unreadable is skipped rather than thrown, because a half-written file in a
         * folder being synced is a normal thing to come across and not a reason to give up on
         * every other device's data.
         */
        fun decode(text: String): SyncDocument? = runCatching {
            json.decodeFromString(serializer(), text).takeIf { it.app == APP }
        }.getOrNull()
    }
}

/** Everything that can be deleted, named so a tombstone knows what it is about. */
enum class SyncKind {
    PROFILE, WEIGHT, MEASUREMENT, WATER, FAST, GOAL, MACRO_TARGET
}

/**
 * A record that is gone.
 *
 * Kept for a while and then forgotten: see [SyncMerge.TOMBSTONE_LIFETIME_MILLIS]. Keeping them
 * forever would grow the file without limit, and dropping them at once would let a device that
 * has been in a drawer for a week undo the deletion when it comes back.
 */
@Serializable
data class SyncDeletion(
    val kind: SyncKind,
    val syncId: String,
    val deletedAtUtcMillis: Long,
)

@Serializable
data class SyncProfile(
    val syncId: String,
    val name: String,
    val position: Int,
    val createdAtUtcMillis: Long,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 7,
    val reminderMinute: Int = 30,
    val reminderDays: String = "",
    val updatedAtUtcMillis: Long,
)

/**
 * One weigh-in.
 *
 * The Health Connect identifier is deliberately not carried across. It names a record in one
 * phone's Health Connect and means nothing on another, and copying it over would make the second
 * phone believe it had already written a weight that it never wrote.
 */
@Serializable
data class SyncWeight(
    val syncId: String,
    val profileSyncId: String,
    val timestampUtcMillis: Long,
    val zoneOffsetSeconds: Int,
    val localDate: String,
    val grams: Int,
    val bodyFatPercent: Double? = null,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val source: String,
    val updatedAtUtcMillis: Long,
)

@Serializable
data class SyncMeasurement(
    val syncId: String,
    val profileSyncId: String,
    val timestampUtcMillis: Long,
    val localDate: String,
    val type: String,
    val valueMm: Int,
    val note: String? = null,
    val updatedAtUtcMillis: Long,
)

@Serializable
data class SyncWater(
    val syncId: String,
    val profileSyncId: String,
    val timestampUtcMillis: Long,
    val localDate: String,
    val millilitres: Int,
    val updatedAtUtcMillis: Long,
)

@Serializable
data class SyncFast(
    val syncId: String,
    val profileSyncId: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long? = null,
    val targetMinutes: Int,
    val note: String? = null,
    val updatedAtUtcMillis: Long,
)

@Serializable
data class SyncGoal(
    val syncId: String,
    val profileSyncId: String,
    val direction: String,
    val startGrams: Int,
    val targetGrams: Int,
    val startDate: String,
    val targetDate: String? = null,
    val milestoneStepGrams: Int,
    val active: Boolean,
    val updatedAtUtcMillis: Long,
)

@Serializable
data class SyncMacroTarget(
    val syncId: String,
    val profileSyncId: String,
    val dayOfWeek: String? = null,
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val basis: String,
    val updatedAtUtcMillis: Long,
)

/**
 * The settings that describe the person rather than the phone.
 *
 * Whether this phone talks to Health Connect is left out on purpose. It is a fact about one
 * device's connection to one Health Connect, and copying it to a second phone would have both of
 * them writing the same weights into the same place.
 */
@Serializable
data class SyncSettings(
    val weightUnit: String,
    val lengthUnit: String,
    val themeMode: String,
    val heightMm: Int,
    val sex: String,
    val birthYear: Int,
    val activityLevel: String,
    val trendWindowDays: Int,
    val milestoneStepGrams: Int,
    val updatedAtUtcMillis: Long,
)
