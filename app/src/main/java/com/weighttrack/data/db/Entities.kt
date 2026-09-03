package com.weighttrack.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Every row that can be synced carries two extra columns, and they work as a pair.
 *
 * `stampDeviceId` is the device that made the version of the row that is here, and `stampMillis`
 * is the time that came with it. Together they are the record's `SyncStamp`, which is what
 * decides whose edit wins when two phones disagree.
 *
 * The pair is only current while `stampMillis` equals `updatedAtUtcMillis`. Anything that edits a
 * row writes a new `updatedAtUtcMillis` and leaves the stamp alone, so the two stop matching and
 * that is exactly how the sync store knows the row has been changed here since it was last
 * published. It stamps it afresh on the way out, from the hybrid clock, in this device's name.
 *
 * Deliberately not stamped at the moment of the edit. Doing that would put a clock and a device
 * name into every repository that writes a row, and a single method that forgot would produce a
 * row that silently sorts wrong on another phone forever. This way, code that has never heard of
 * sync gets the right answer by not doing anything.
 */

/**
 * Whose readings these are.
 *
 * A household shares one scale far more often than it shares a phone, so the app has to be able
 * to keep two people apart. There is always at least one profile and it can never be deleted:
 * everything else in the database points at one, and a row with nowhere to belong is a row
 * nobody can ever see again.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Sorted by this rather than by name, so switching does not reshuffle under a thumb. */
    val position: Int,
    val createdAtUtcMillis: Long,
    /**
     * The reminder settings live on the row rather than in preferences.
     *
     * Two people in a house weigh themselves at different times, and settings held under a
     * key per profile would outlive the profile and quietly come back when the identifier
     * was reused. Deleting the row takes the reminder with it.
     */
    @ColumnInfo(defaultValue = "0") val reminderEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "7") val reminderHour: Int = 7,
    @ColumnInfo(defaultValue = "30") val reminderMinute: Int = 30,
    /** Comma separated day names. Empty means every day. */
    @ColumnInfo(defaultValue = "''") val reminderDays: String = "",
    /**
     * Whether this profile is the one Health Connect exchanges weights with.
     *
     * At most one may be. Health Connect has no idea which member of a household a weight
     * belongs to, so pretending it can keep two people apart would mix them together on the
     * way out and read somebody else's back in.
     */
    @ColumnInfo(defaultValue = "0") val healthConnectEnabled: Boolean = false,
    /**
     * What this row is called on every device.
     *
     * A row's own identifier is a counting number handed out by whichever database created it,
     * so two phones will both have a row 7 meaning different things. Sync needs a name that
     * travels, and this is it. Blank means the row predates sync and has not been given one yet.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
    @ColumnInfo(defaultValue = "0") val updatedAtUtcMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
    /**
     * The body these figures describe.
     *
     * On the profile rather than in the app's settings, where they used to live. A household
     * sharing a scale shares one phone, and one height, one sex, one year of birth and one
     * activity level for everybody meant switching person computed their BMI, their healthy
     * range, their body fat, their basal rate and their expenditure from somebody else's body.
     * Every one of those numbers looked perfectly ordinary and every one of them was wrong.
     *
     * Empty and zero mean "not said". A profile added after somebody else has filled these in
     * starts blank rather than inheriting a stranger's.
     */
    @ColumnInfo(defaultValue = "0") val heightMm: Int = 0,
    @ColumnInfo(defaultValue = "''") val sex: String = "",
    @ColumnInfo(defaultValue = "0") val birthYear: Int = 0,
    @ColumnInfo(defaultValue = "''") val activityLevel: String = "",
)

/**
 * Stored columns stay primitive on purpose. Enums and dates are held as text, so the database
 * can be read by any tool, a schema migration never has to chase a converter, and an export
 * is a straight dump of what is already there.
 */
@Entity(
    tableName = "weight_entries",
    indices = [
        Index(value = ["profileId", "timestampUtcMillis"]),
        Index(value = ["timestampUtcMillis"]),
        Index(value = ["localDate"]),
        // Unique per profile, not globally. A record identifier is only an identity within
        // one person's history: restoring the same backup, or importing the same file, for a
        // second person must give them their own rows rather than move the first person's.
        Index(value = ["profileId", "clientRecordId"], unique = true),
        Index(value = ["healthConnectId"]),
    ],
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose row this is. One is the profile every upgrade lands in. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    val timestampUtcMillis: Long,
    val zoneOffsetSeconds: Int,
    /**
     * ISO date in the zone the reading was taken in, denormalised so day grouping never has to
     * re-derive it. A reading logged at 11pm stays on the day the person took it, even after
     * they fly somewhere else.
     */
    val localDate: String,
    val grams: Int,
    val bodyFatPercent: Double?,
    val note: String?,
    /** Comma separated EntryTag names. Empty string means none. */
    val tags: String,
    val source: String,
    val clientRecordId: String,
    /**
     * The version of this row Health Connect has been told about.
     *
     * Zero means it has never been sent. Compared against [updatedAtUtcMillis] rather than
     * against a clock: an export watermark measured in wall time misses every row that arrives
     * from another device carrying that device's timestamps, which is most of a history after a
     * phone switch, and those rows would never reach Health Connect at all.
     */
    @ColumnInfo(defaultValue = "0") val healthExportedAtUtcMillis: Long = 0,
    /**
     * What the scale said beyond the weight.
     *
     * All null for a weight typed in, imported, or read from a scale that only weighs. Kept on
     * the weigh-in rather than in a table of its own: it is one row per reading either way, and
     * a second table would need its own name that travels, its own tombstones and its own place
     * in every backup for no gain.
     */
    @ColumnInfo(defaultValue = "NULL") val muscleMassGrams: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val fatFreeMassGrams: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val softLeanMassGrams: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val bodyWaterMassGrams: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val musclePercent: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val impedanceOhms: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val basalMetabolismKcal: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val scaleBmi: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val scaleHeightMm: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val scaleUserId: Int? = null,
    /** Which scale, which reader understood it, and what the figures are worth. */
    @ColumnInfo(defaultValue = "NULL") val compositionDevice: String? = null,
    @ColumnInfo(defaultValue = "NULL") val compositionProtocol: String? = null,
    @ColumnInfo(defaultValue = "NULL") val compositionQuality: String? = null,
    /**
     * Which app in Health Connect wrote the reading, and on what.
     *
     * Health Connect is a shared pool and a weigh-in in it was written by somebody. Without this
     * there is no answering "where did this come from" or "stop taking these", and a reading
     * imported through two apps looked like two weigh-ins a minute apart.
     */
    @ColumnInfo(defaultValue = "NULL") val originPackage: String? = null,
    @ColumnInfo(defaultValue = "NULL") val originDevice: String? = null,
    val healthConnectId: String?,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
)

@Entity(
    tableName = "measurements",
    indices = [
        Index(value = ["profileId", "timestampUtcMillis"]),
        Index(value = ["timestampUtcMillis"]),
        Index(value = ["type", "timestampUtcMillis"]),
    ],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose row this is. One is the profile every upgrade lands in. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    val timestampUtcMillis: Long,
    val localDate: String,
    val type: String,
    val valueMm: Int,
    val note: String?,
    /**
     * Carried forward from the last set rather than measured again.
     *
     * A set of measurements is thirteen sites and almost nobody changes all thirteen. Writing the
     * unchanged ones keeps each set complete, so a chart of one site is not full of holes, but
     * the difference matters: a carried value is a fact about the last time somebody measured,
     * not about today, and anything reading the history should be able to tell.
     */
    @ColumnInfo(defaultValue = "0") val carried: Boolean = false,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",

    /**
     * What this row is called on every device.
     *
     * A row's own identifier is a counting number handed out by whichever database created it,
     * so two phones will both have a row 7 meaning different things. Sync needs a name that
     * travels, and this is it. Blank means the row predates sync and has not been given one yet.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

@Entity(tableName = "goals", indices = [Index(value = ["profileId"])])
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose row this is. One is the profile every upgrade lands in. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    val direction: String,
    val startGrams: Int,
    val targetGrams: Int,
    val startDate: String,
    val targetDate: String?,
    val milestoneStepGrams: Int,
    val active: Boolean,
    val createdAtUtcMillis: Long,
    /**
     * How far either way still counts as being at the target.
     *
     * A kilogram for every goal that existed before this column, which is the constant the
     * maintain tolerance used to be.
     */
    @ColumnInfo(defaultValue = "1000") val bandGrams: Int = com.weighttrack.core.model.DEFAULT_GOAL_BAND_GRAMS,
    /**
     * What this row is called on every device.
     *
     * A row's own identifier is a counting number handed out by whichever database created it,
     * so two phones will both have a row 7 meaning different things. Sync needs a name that
     * travels, and this is it. Blank means the row predates sync and has not been given one yet.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
    @ColumnInfo(defaultValue = "0") val updatedAtUtcMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
)

@Entity(
    tableName = "water_entries",
    indices = [
        Index(value = ["profileId", "localDate"]),
        Index(value = ["timestampUtcMillis"]),
        Index(value = ["localDate"]),
    ],
)
data class WaterEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose row this is. One is the profile every upgrade lands in. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    val timestampUtcMillis: Long,
    /** The day the drink counts towards, in the zone the person was standing in. */
    val localDate: String,
    val millilitres: Int,
    val healthConnectId: String?,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
    /**
     * What this row is called on every device.
     *
     * A row's own identifier is a counting number handed out by whichever database created it,
     * so two phones will both have a row 7 meaning different things. Sync needs a name that
     * travels, and this is it. Blank means the row predates sync and has not been given one yet.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

/**
 * One fast. An open fast has a null end, and there is at most one of those at a time.
 *
 * Start and end are stored as instants rather than a duration so an edit can correct either
 * end without the other drifting, which is the complaint people have about fasting apps that
 * refuse to let you fix a forgotten stop.
 */
@Entity(
    tableName = "fasts",
    indices = [
        Index(value = ["profileId", "startUtcMillis"]),
        Index(value = ["startUtcMillis"]),
        Index(value = ["endUtcMillis"]),
    ],
)
data class FastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose row this is. One is the profile every upgrade lands in. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    val startUtcMillis: Long,
    val endUtcMillis: Long?,
    /** The preset the fast was started against, in whole minutes. */
    val targetMinutes: Int,
    val note: String?,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
    /**
     * What this row is called on every device.
     *
     * A row's own identifier is a counting number handed out by whichever database created it,
     * so two phones will both have a row 7 meaning different things. Sync needs a name that
     * travels, and this is it. Blank means the row predates sync and has not been given one yet.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

/**
 * A progress photo.
 *
 * Only the file name is stored, never a path: the directory is decided at runtime, so a row
 * written on one install still resolves after a restore into a different data directory. The
 * image itself lives in app-private storage and is excluded from backup like everything else.
 */
@Entity(
    tableName = "progress_photos",
    indices = [
        Index(value = ["profileId", "timestampUtcMillis"]),
        Index(value = ["timestampUtcMillis"]),
        Index(value = ["fileName"], unique = true),
    ],
)
data class ProgressPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose row this is. One is the profile every upgrade lands in. */
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    val timestampUtcMillis: Long,
    val localDate: String,
    val fileName: String,
    /** The trend weight when the photo was taken, so a comparison can show the change. */
    val weightGrams: Int?,
    val note: String?,
)

/**
 * A food, shared by everybody on the phone.
 *
 * Deliberately not tied to a profile. A food is a fact about a product, not about a person, and
 * a household cooking together shares its recipes. What belongs to a person is the eating of it.
 *
 * A barcode is indexed but not unique: two brands reuse a code often enough that a unique
 * constraint would refuse a real product.
 */
@Entity(
    tableName = "foods",
    indices = [
        Index(value = ["barcode"]),
        Index(value = ["name"]),
        Index(value = ["lastUsedAtUtcMillis"]),
    ],
)
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String?,
    val barcode: String?,
    /** Everything is per hundred grams, so nothing has to remember which basis it used. */
    val kcalPer100g: Double,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val fibrePer100g: Double?,
    val sugarPer100g: Double?,
    val saltPer100g: Double?,
    /** What one serving weighs, when the label said. */
    val servingGrams: Double?,
    val origin: String,
    val favourite: Boolean = false,
    /** Zero until it has been eaten, which is what puts it in the recents list. */
    val lastUsedAtUtcMillis: Long = 0,
    /** When it was last read from the service it came from. Zero for anything typed in here. */
    @ColumnInfo(defaultValue = "0") val fetchedAtUtcMillis: Long = 0,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
    /**
     * What this row is called on every device. See the note on the weight entry.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

/** Something cooked from other foods, whose nutrition is worked out rather than stored. */
@Entity(tableName = "recipes", indices = [Index(value = ["name"])])
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** What the whole recipe makes, so a portion can be worked out from it. */
    val servings: Int,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
    /**
     * What this row is called on every device. See the note on the weight entry.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

@Entity(
    tableName = "recipe_items",
    indices = [Index(value = ["recipeId"]), Index(value = ["foodId"])],
)
data class RecipeItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val foodId: Long,
    val grams: Double,
    /**
     * What this row is called on every device. See the note on the weight entry.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

/**
 * One thing eaten, on one day, by one person.
 *
 * The nutrition is copied onto the row rather than looked up through the food. A label
 * corrected next month must not rewrite what last month's days added up to, and a food deleted
 * must not take a day's total with it. The food identifier is kept only so "eat this again"
 * knows what to offer.
 */
@Entity(
    tableName = "food_log_entries",
    indices = [
        Index(value = ["profileId", "localDate"]),
        Index(value = ["foodId"]),
    ],
)
data class FoodLogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "1") val profileId: Long = 1,
    /** The day it counts towards, in the zone the person was standing in. */
    val localDate: String,
    val meal: String,
    /** Null for a quick-add, and for anything whose food has since been deleted. */
    val foodId: Long?,
    val name: String,
    /** Null for a quick-add, which is a number of calories and nothing else. */
    val grams: Double?,
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val loggedAtUtcMillis: Long,
    /**
     * What this row is called on every device. See the note on the weight entry.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
    @ColumnInfo(defaultValue = "0") val updatedAtUtcMillis: Long = 0,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
)

/**
 * What a day is meant to come to, for one person.
 *
 * One row with no day is the target for any day without one of its own. Stored as grams
 * whatever the person typed, because a percentage of a calorie figure that later changes would
 * silently mean something different.
 */
@Entity(
    tableName = "macro_targets",
    indices = [Index(value = ["profileId", "dayOfWeek"], unique = true)],
)
data class MacroTargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** Null for the target that applies to every day without one of its own. */
    val dayOfWeek: String?,
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    /** Only how it is shown and edited. What is stored is always grams. */
    val basis: String,
    val updatedAtUtcMillis: Long,
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
    /**
     * What this row is called on every device.
     *
     * A row's own identifier is a counting number handed out by whichever database created it,
     * so two phones will both have a row 7 meaning different things. Sync needs a name that
     * travels, and this is it. Blank means the row predates sync and has not been given one yet.
     */
    @ColumnInfo(defaultValue = "''") val syncId: String = newSyncId(),
)

/**
 * A row that was deleted, remembered so the deletion can travel.
 *
 * Without this a delete never leaves the phone it happened on: the other device still holds the
 * row, has no reason to drop it, and hands it straight back on the next sync. A reading that
 * comes home every time it is deleted is the most irritating way for sync to be wrong.
 *
 * These are written whether or not sync is switched on. Turning it on later would otherwise
 * bring back everything deleted before that moment.
 */
@Entity(tableName = "deletions", primaryKeys = ["kind", "syncId", "profileSyncId"])
data class DeletionEntity(
    val kind: String,
    val syncId: String,
    val deletedAtUtcMillis: Long,
    /**
     * Whose row it was.
     *
     * A record's name is only unique within a profile, so a deletion has to say which one or it
     * takes another person's identically named row with it. Blank means "whoever holds it",
     * which is what a profile's own deletion means.
     */
    @ColumnInfo(defaultValue = "''") val profileSyncId: String = "",
    @ColumnInfo(defaultValue = "0") val stampMillis: Long = 0,
    @ColumnInfo(defaultValue = "''") val stampDeviceId: String = "",
)

/**
 * A device this one syncs with, and how far it has caught up with it.
 *
 * Kept so a deletion can be forgotten on evidence rather than on a calendar. Until every device
 * that is not retired has confirmed it has seen a deletion, the tombstone stays, so the phone
 * that spent nine months in a drawer cannot bring the row back when it returns.
 *
 * [observedThroughMillis] is the newest edit from that device this one holds. Published in the
 * sync file, where the others read it as an acknowledgement.
 */
@Entity(tableName = "sync_peers")
data class SyncPeerEntity(
    @PrimaryKey val deviceId: String,
    val lastSeenAtUtcMillis: Long = 0,
    /** When somebody said that device was gone for good, or zero while it is expected back. */
    val retiredAtUtcMillis: Long = 0,
    /** When that was last decided either way, so retiring can be undone. */
    val retirementDecidedAtUtcMillis: Long = 0,
    val observedThroughMillis: Long = 0,
)

/**
 * A name a row keeps on every device.
 *
 * A plain random identifier rather than anything derived from the contents. Two weigh-ins on the
 * same morning at the same weight are still two weigh-ins, and hashing the contents would quietly
 * merge them.
 */
fun newSyncId(): String = java.util.UUID.randomUUID().toString().replace("-", "")
