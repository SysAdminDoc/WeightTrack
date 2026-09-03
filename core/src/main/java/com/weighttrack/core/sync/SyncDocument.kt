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
    val foods: List<SyncFood> = emptyList(),
    val recipes: List<SyncRecipe> = emptyList(),
    val recipeItems: List<SyncRecipeItem> = emptyList(),
    val foodLog: List<SyncFoodLogEntry> = emptyList(),
    val settings: SyncSettings? = null,
    /**
     * What has been deleted, and when.
     *
     * Without these a delete never travels: the other device still holds the row, sees no reason
     * to drop it, and hands it straight back. A record that comes home every time it is deleted
     * is the single most irritating way for sync to be wrong.
     */
    val deletions: List<SyncDeletion> = emptyList(),
    /**
     * Every device this one has heard of, including itself.
     *
     * Shared so that a phone which has never met another one directly still knows it exists and
     * still waits for it before forgetting a deletion.
     */
    val peers: List<SyncPeer> = emptyList(),
    /**
     * How far this device has caught up with each of the others.
     *
     * This is the acknowledgement the tombstone rule reads. See [SyncObservation].
     */
    val observed: List<SyncObservation> = emptyList(),
) {

    /**
     * The same document with every record saying which device made it.
     *
     * A file written before stamps existed, or by a device that fills nothing in, leaves the
     * name blank. Blank has to mean "whoever wrote this file", and it has to be resolved before
     * the merge rather than during it: a record relayed by a third phone would otherwise be
     * re-attributed to the relay, and then which version of a record wins would depend on which
     * files happened to be in the folder.
     */
    fun attributed(): SyncDocument {
        fun name(current: String) = current.ifBlank { deviceId }
        return copy(
            profiles = profiles.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            weights = weights.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            measurements = measurements.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            water = water.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            fasts = fasts.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            goals = goals.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            macroTargets = macroTargets.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            foods = foods.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            recipes = recipes.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            recipeItems = recipeItems.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            foodLog = foodLog.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            settings = settings?.let { it.copy(stampDeviceId = name(it.stampDeviceId)) },
            deletions = deletions.map { it.copy(stampDeviceId = name(it.stampDeviceId)) },
        )
    }

    /** The newest stamp this document holds from each device, its own edits included. */
    fun highestStampPerDevice(): Map<String, Long> {
        val highest = HashMap<String, Long>()
        fun note(deviceId: String, millis: Long) {
            val current = highest[deviceId]
            if (current == null || millis > current) highest[deviceId] = millis
        }
        profiles.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        weights.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        measurements.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        water.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        fasts.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        goals.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        macroTargets.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        foods.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        recipes.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        recipeItems.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        foodLog.forEach { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        settings?.let { note(it.stampDeviceId, it.updatedAtUtcMillis) }
        deletions.forEach { note(it.stampDeviceId, it.deletedAtUtcMillis) }
        return highest.filterKeys { it.isNotBlank() }
    }

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
            json.decodeFromString(serializer(), text).takeIf { it.app == APP }?.attributed()
        }.getOrNull()
    }
}

/** Everything that can be deleted, named so a tombstone knows what it is about. */
enum class SyncKind {
    PROFILE, WEIGHT, MEASUREMENT, WATER, FAST, GOAL, MACRO_TARGET,

    // The food side. Foods and recipes belong to nobody in particular: a food is a fact about a
    // product and a household cooking together shares its recipes. Only the eating belongs to a
    // person.
    FOOD, RECIPE, RECIPE_ITEM, FOOD_LOG,
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
    /**
     * Whose row it was.
     *
     * A record's name is only unique within a profile: the same backup restored for two people,
     * or the same file imported twice, gives both of them rows called the same thing. Without
     * this, deleting one person's morning takes the other person's with it.
     *
     * Blank means "whoever holds it", which is what a profile's own tombstone says and what a
     * file written before this field existed says.
     */
    val profileSyncId: String = "",
    /** Which device deleted it. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
)

/**
 * A device this one knows about.
 *
 * Kept so a deletion can be forgotten on evidence rather than on a calendar. A tombstone is only
 * safe to drop once every device that could still be holding the row has seen that it is gone,
 * and that question cannot be answered without a list of who those devices are.
 *
 * Retirement is how somebody says a phone is not coming back. It is the only thing that lets the
 * others stop waiting for it, and it only ever affects when deletions are forgotten: a retired
 * device's readings still merge exactly as they did, so retiring one by mistake loses nothing.
 */
@Serializable
data class SyncPeer(
    val deviceId: String,
    /** When a file written by that device was last read. */
    val lastSeenAtUtcMillis: Long = 0,
    /** When somebody said it was gone for good, or zero while it is still expected back. */
    val retiredAtUtcMillis: Long = 0,
    /**
     * When retirement was last decided, either way.
     *
     * Kept apart from the flag so retiring can be undone. Taking the largest [retiredAtUtcMillis]
     * across everybody's files would settle it, but only in one direction: a phone brought back
     * by mistake could never be brought back, because every other device's file would still be
     * carrying the retirement and would keep putting it back.
     */
    val retirementDecidedAtUtcMillis: Long = 0,
) {
    val isRetired: Boolean get() = retiredAtUtcMillis > 0

    /**
     * When this entry's retirement was decided.
     *
     * A file written before the decision was recorded separately says only when the device was
     * retired, and that moment is the decision.
     */
    val decidedAtUtcMillis: Long get() = maxOf(retirementDecidedAtUtcMillis, retiredAtUtcMillis)
}

/**
 * How far one device has caught up with another.
 *
 * A document's own list says, for each device it has ever heard of, the newest edit from that
 * device it holds. Read from somebody else's file it is an acknowledgement: a phone whose list
 * says it holds everything device X made up to Tuesday has seen anything X deleted before then,
 * and is not going to hand it back.
 *
 * Small by construction. There is one entry per device in the household, not one per record.
 */
@Serializable
data class SyncObservation(
    /** The device the edits came from. */
    val deviceId: String,
    /** The newest stamp from that device this one holds. */
    val throughMillis: Long,
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
    /**
     * The body this person's figures are worked out from.
     *
     * On the profile because that is whose body it is. These used to travel in [SyncSettings],
     * one height and one year of birth for the whole phone, so a household of two had every
     * BMI, healthy range, body-fat estimate, basal rate and expenditure computed from whichever
     * of them had filled the settings in.
     *
     * Absent in a document written before this existed, which reads as "not said" rather than
     * as zero: a device on an older version must not blank out what the newer one holds.
     */
    val heightMm: Int = 0,
    val sex: String = "",
    val birthYear: Int = 0,
    val activityLevel: String = "",
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
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
    /**
     * What a scale said beyond the weight.
     *
     * Absent in a document written before this existed, and absent for every weight that was
     * typed in. It travels because it belongs to the reading: a person watching their muscle
     * mass has the same right to keep it as they have to keep the weight.
     */
    val muscleMassGrams: Int? = null,
    val fatFreeMassGrams: Int? = null,
    val softLeanMassGrams: Int? = null,
    val bodyWaterMassGrams: Int? = null,
    val musclePercent: Double? = null,
    val impedanceOhms: Double? = null,
    val basalMetabolismKcal: Double? = null,
    val scaleBmi: Double? = null,
    val scaleHeightMm: Int? = null,
    val scaleUserId: Int? = null,
    val compositionDevice: String? = null,
    val compositionProtocol: String? = null,
    val compositionQuality: String? = null,
    /**
     * Which app in Health Connect wrote the reading, and on what.
     *
     * Carried across because it is true wherever the row ends up: the reading did come from that
     * app, and the other phone showing "from your scale's app" beside it is the same answer.
     */
    val originPackage: String? = null,
    val originDevice: String? = null,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
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
    /**
     * Carried forward from the last set rather than measured again.
     *
     * Defaulted, so a document from a device that has not been updated still reads. Without it
     * the receiving phone recorded every carried value as measured, and since the sending
     * phone's own row was never rewritten the two disagreed for good.
     */
    val carried: Boolean = false,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
)

@Serializable
data class SyncWater(
    val syncId: String,
    val profileSyncId: String,
    val timestampUtcMillis: Long,
    val localDate: String,
    val millilitres: Int,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
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
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
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
    /**
     * How far either way still counts as being at the target.
     *
     * Defaulted for a document written before the band existed, so an older device's goal
     * arrives with the kilogram the tolerance used to be rather than with nothing.
     */
    val bandGrams: Int = com.weighttrack.core.model.DEFAULT_GOAL_BAND_GRAMS,
    val active: Boolean,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
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
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
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
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
    /**
     * Which smoother draws the line, by name.
     *
     * Defaulted, so a document written by a device that has not been updated still reads and an
     * older device ignores the field rather than refusing the whole file.
     */
    val smoothingMode: String = com.weighttrack.core.math.SmoothingMode.EMA.name,
)

/**
 * A food.
 *
 * Not scoped to a profile, deliberately. A food is a fact about a product rather than about a
 * person, and a household cooking together shares them. What belongs to a person is the eating.
 *
 * Whether it is a favourite and when it was last eaten stay on the device that did the eating,
 * because "recently used" is a fact about one person's phone.
 */
@Serializable
data class SyncFood(
    val syncId: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val kcalPer100g: Double,
    val proteinPer100g: Double? = null,
    val carbsPer100g: Double? = null,
    val fatPer100g: Double? = null,
    val fibrePer100g: Double? = null,
    val sugarPer100g: Double? = null,
    val saltPer100g: Double? = null,
    val servingGrams: Double? = null,
    val origin: String,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
)

@Serializable
data class SyncRecipe(
    val syncId: String,
    val name: String,
    val servings: Int,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
)

/**
 * One ingredient of a recipe.
 *
 * Both the recipe and the food are named by their travelling names rather than by a row number,
 * which is the only way an ingredient means the same thing on two devices.
 */
@Serializable
data class SyncRecipeItem(
    val syncId: String,
    val recipeSyncId: String,
    val foodSyncId: String,
    val grams: Double,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
)

/**
 * One thing eaten, on one day, by one person.
 *
 * The nutrition travels on the row rather than being looked up through the food, exactly as it is
 * stored: a label corrected next month must not rewrite what last month's days added up to.
 */
@Serializable
data class SyncFoodLogEntry(
    val syncId: String,
    val profileSyncId: String,
    val localDate: String,
    val meal: String,
    /** The food it came from, when it still exists. Null for a quick add. */
    val foodSyncId: String? = null,
    val name: String,
    val grams: Double? = null,
    val kcal: Double,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val loggedAtUtcMillis: Long,
    val updatedAtUtcMillis: Long,
    /** Which device made this version. See [SyncStamp]. Blank means the file's own writer. */
    val stampDeviceId: String = "",
)
