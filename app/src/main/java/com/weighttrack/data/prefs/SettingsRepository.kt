package com.weighttrack.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.model.ActivityLevel
import com.weighttrack.core.model.LengthUnit
import com.weighttrack.core.model.Sex
import com.weighttrack.core.model.ThemeMode
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.model.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val weightUnit: WeightUnit = WeightUnit.KG,
    val lengthUnit: LengthUnit = LengthUnit.CM,
    val themeMode: ThemeMode = ThemeMode.AMOLED,
    val dynamicColor: Boolean = false,
    val profile: UserProfile = UserProfile(),
    val trendWindowDays: Int = TrendEngine.DEFAULT_WINDOW_DAYS,
    /** Zero means "derive a round number from the current weight unit". */
    val milestoneStepGrams: Int = 0,
    val onboardingComplete: Boolean = false,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 7,
    val reminderMinute: Int = 30,
    val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val appLockEnabled: Boolean = false,
    val waterTargetMl: Int = 2_000,
    val waterServingMl: Int = 250,
    val weeklySummaryEnabled: Boolean = false,
    val weeklySummaryDay: DayOfWeek = DayOfWeek.SUNDAY,
    val weeklySummaryHour: Int = 19,
    /** The Bluetooth scale last used, so the next weigh-in does not start with a scan. */
    /** Whose readings the app is showing. */
    val activeProfileId: Long = 1L,
    /** Whether the reminder that existed before profiles has been moved onto one. */
    val legacyReminderAdopted: Boolean = false,
    /** Whether the demographics that existed before profiles have been moved onto one. */
    val legacyDemographicsAdopted: Boolean = false,
    /** Food logging is off until somebody asks for it, so the weight-only app stays clean. */
    val nutritionEnabled: Boolean = false,
    /** Whoever uses the app supplies their own, since this one will not ship a shared key. */
    val usdaApiKey: String? = null,
    /** Keep only the lowest weigh-in of each day when importing from Health Connect. */
    val importLowestOfDay: Boolean = false,
    /** When the settings that describe the person last changed, for sync to compare. */
    val updatedAtUtcMillis: Long = 0,
    val scaleAddress: String? = null,
    val scaleName: String? = null,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val secrets: com.weighttrack.security.SecretStore,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun setWeightUnit(unit: WeightUnit) = stamped { it[Keys.WEIGHT_UNIT] = unit.name }

    suspend fun setLengthUnit(unit: LengthUnit) = stamped { it[Keys.LENGTH_UNIT] = unit.name }

    suspend fun setThemeMode(mode: ThemeMode) = stamped { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setProfile(profile: UserProfile) = stamped {
        it[Keys.HEIGHT_MM] = profile.heightMm
        it[Keys.SEX] = profile.sex.name
        it[Keys.BIRTH_YEAR] = profile.birthYear
        it[Keys.ACTIVITY_LEVEL] = profile.activityLevel.name
    }

    suspend fun setHeightMm(heightMm: Int) = stamped { it[Keys.HEIGHT_MM] = heightMm }

    suspend fun setTrendWindowDays(days: Int) = stamped {
        it[Keys.TREND_WINDOW_DAYS] = days.coerceIn(TrendEngine.MIN_WINDOW_DAYS, TrendEngine.MAX_WINDOW_DAYS)
    }

    suspend fun setMilestoneStepGrams(grams: Int) = stamped { it[Keys.MILESTONE_STEP_GRAMS] = grams }

    /** The folder the weekly copy goes into, or null when nobody has chosen one. */
    /** The same one-off rewrite for the USDA key. See SyncPreferences.protectStoredSecrets. */
    suspend fun protectStoredSecrets() {
        val stored = dataStore.data.first()[Keys.USDA_API_KEY] ?: return
        if (secrets.isProtected(stored)) return
        val protected = secrets.protect(stored) ?: return
        edit { it[Keys.USDA_API_KEY] = protected }
    }

    suspend fun autoBackupFolder(): String? = dataStore.data.first()[Keys.AUTO_BACKUP_FOLDER]

    suspend fun setAutoBackupFolder(uri: String?) = edit {
        if (uri == null) it.remove(Keys.AUTO_BACKUP_FOLDER) else it[Keys.AUTO_BACKUP_FOLDER] = uri
    }

    suspend fun lastAutoBackup(): Long? = dataStore.data.first()[Keys.LAST_AUTO_BACKUP]

    suspend fun setLastAutoBackup(at: Long) = edit { it[Keys.LAST_AUTO_BACKUP] = at }

    /** Whether the last attempt found the folder gone, so the screen can say so. */
    suspend fun autoBackupProblem(): Boolean =
        dataStore.data.first()[Keys.AUTO_BACKUP_PROBLEM] ?: false

    suspend fun setAutoBackupProblem(problem: Boolean) = edit {
        it[Keys.AUTO_BACKUP_PROBLEM] = problem
    }

    suspend fun healthChangesToken(profileId: Long): String? =
        dataStore.data.first()[Keys.healthChangesToken(profileId)]

    suspend fun setHealthChangesToken(profileId: Long, token: String?) = edit {
        val key = Keys.healthChangesToken(profileId)
        if (token == null) it.remove(key) else it[key] = token
    }

    /**
     * Whether the question of who owns Health Connect has been answered.
     *
     * Without this, 'nobody holds it' means both 'nobody has connected yet' and 'somebody
     * turned it off'. The first should claim whoever is here; the second must not, or switching
     * it off becomes an hour-long pause that then points Health Connect at whoever happens to be
     * on screen and pours one person's history into another's.
     */
    suspend fun healthConnectDecided(): Boolean =
        dataStore.data.first()[Keys.HEALTH_CONNECT_DECIDED] ?: false

    suspend fun setHealthConnectDecided() = edit { it[Keys.HEALTH_CONNECT_DECIDED] = true }
    /**
     * How far the Health Connect import has read, for one person.
     *
     * What makes losing the cursor cheap. Without it, a token the provider had forgotten meant
     * reading five years of records again, so a bad minute for the provider cost the most
     * expensive query the app can make, every hour, until it stopped.
     */
    suspend fun healthImportedThrough(profileId: Long): Long =
        dataStore.data.first()[Keys.healthImportedThrough(profileId)] ?: 0

    suspend fun setHealthImportedThrough(profileId: Long, at: Long) = edit {
        it[Keys.healthImportedThrough(profileId)] = at
    }

    /**
     * Whether the readings already sent to Health Connect went with their body-fat figures.
     *
     * False until a run has sent something with the body-fat grant in hand. What it guards is
     * the one-off resend: a reading exported while body fat was not allowed went across without
     * it and was marked done, so allowing it afterwards would reach nothing already recorded.
     */
    suspend fun healthBodyFatExported(profileId: Long): Boolean =
        dataStore.data.first()[Keys.healthBodyFatExported(profileId)] ?: false

    suspend fun setHealthBodyFatExported(profileId: Long, exported: Boolean) = edit {
        it[Keys.healthBodyFatExported(profileId)] = exported
    }
    /**
     * Deletions Health Connect has already been told about, for one person.
     *
     * Kept as a list of names rather than a moment in time. A tombstone that arrives from
     * another device carries that device's clock, so a wall-time mark skips it and the record
     * this phone wrote is never taken out of Health Connect. Names also survive the tombstone
     * itself being forgotten, which happens after six months.
     */
    suspend fun healthDeletionsSent(profileId: Long): Set<String> =
        dataStore.data.first()[Keys.healthDeletionsSent(profileId)].orEmpty()

    suspend fun setHealthDeletionsSent(profileId: Long, names: Set<String>) = edit {
        it[Keys.healthDeletionsSent(profileId)] = names
    }

    suspend fun setOnboardingComplete(complete: Boolean) = edit { it[Keys.ONBOARDING_COMPLETE] = complete }

    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int, days: Set<DayOfWeek>) = edit {
        it[Keys.REMINDER_ENABLED] = enabled
        it[Keys.REMINDER_HOUR] = hour.coerceIn(0, 23)
        it[Keys.REMINDER_MINUTE] = minute.coerceIn(0, 59)
        it[Keys.REMINDER_DAYS] = days.map { day -> day.name }.toSet()
    }

    suspend fun setReminderEnabled(enabled: Boolean) = edit { it[Keys.REMINDER_ENABLED] = enabled }

    suspend fun setAppLockEnabled(enabled: Boolean) = edit { it[Keys.APP_LOCK_ENABLED] = enabled }

    suspend fun setWaterTargetMl(millilitres: Int) = edit {
        it[Keys.WATER_TARGET_ML] = millilitres.coerceIn(200, 10_000)
    }

    suspend fun setWaterServingMl(millilitres: Int) = edit {
        it[Keys.WATER_SERVING_ML] = millilitres.coerceIn(25, 2_000)
    }

    suspend fun setWeeklySummary(enabled: Boolean, day: DayOfWeek, hour: Int) = edit {
        it[Keys.WEEKLY_SUMMARY_ENABLED] = enabled
        it[Keys.WEEKLY_SUMMARY_DAY] = day.name
        it[Keys.WEEKLY_SUMMARY_HOUR] = hour.coerceIn(0, 23)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    /**
     * An edit that records when it happened, for sync to compare against another device's.
     *
     * Only the settings that describe the person are stamped. Which scale this phone pairs with,
     * or which profile is open on it, are facts about the phone: stamping those would let
     * choosing a scale on one device overwrite a real settings change made on the other.
     */
    private suspend fun stamped(
        block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) = edit {
        block(it)
        it[Keys.SETTINGS_UPDATED_AT] = System.currentTimeMillis()
    }

    /** Writes settings that arrived from another device, keeping the time they were changed. */
    suspend fun applySynced(
        weightUnit: WeightUnit,
        lengthUnit: LengthUnit,
        themeMode: ThemeMode,
        heightMm: Int,
        sex: Sex,
        birthYear: Int,
        activityLevel: ActivityLevel,
        trendWindowDays: Int,
        milestoneStepGrams: Int,
        updatedAtUtcMillis: Long,
    ) = edit {
        it[Keys.WEIGHT_UNIT] = weightUnit.name
        it[Keys.LENGTH_UNIT] = lengthUnit.name
        it[Keys.THEME_MODE] = themeMode.name
        it[Keys.HEIGHT_MM] = heightMm
        it[Keys.SEX] = sex.name
        it[Keys.BIRTH_YEAR] = birthYear
        it[Keys.ACTIVITY_LEVEL] = activityLevel.name
        it[Keys.TREND_WINDOW_DAYS] = trendWindowDays
        it[Keys.MILESTONE_STEP_GRAMS] = milestoneStepGrams
        // Kept as it arrived rather than set to now, or this device would look like the most
        // recent editor and hand its own copy straight back.
        it[Keys.SETTINGS_UPDATED_AT] = updatedAtUtcMillis
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        updatedAtUtcMillis = this[Keys.SETTINGS_UPDATED_AT] ?: 0,
        weightUnit = enumOrDefault(this[Keys.WEIGHT_UNIT], WeightUnit.entries, WeightUnit.KG),
        lengthUnit = enumOrDefault(this[Keys.LENGTH_UNIT], LengthUnit.entries, LengthUnit.CM),
        themeMode = enumOrDefault(this[Keys.THEME_MODE], ThemeMode.entries, ThemeMode.AMOLED),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: false,
        profile = UserProfile(
            heightMm = this[Keys.HEIGHT_MM] ?: 0,
            sex = enumOrDefault(this[Keys.SEX], Sex.entries, Sex.MALE),
            birthYear = this[Keys.BIRTH_YEAR] ?: 0,
            activityLevel = enumOrDefault(this[Keys.ACTIVITY_LEVEL], ActivityLevel.entries, ActivityLevel.LIGHT),
        ),
        trendWindowDays = this[Keys.TREND_WINDOW_DAYS] ?: TrendEngine.DEFAULT_WINDOW_DAYS,
        milestoneStepGrams = this[Keys.MILESTONE_STEP_GRAMS] ?: 0,
        onboardingComplete = this[Keys.ONBOARDING_COMPLETE] ?: false,
        reminderEnabled = this[Keys.REMINDER_ENABLED] ?: false,
        reminderHour = this[Keys.REMINDER_HOUR] ?: 7,
        reminderMinute = this[Keys.REMINDER_MINUTE] ?: 30,
        reminderDays = this[Keys.REMINDER_DAYS]
            ?.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: DayOfWeek.entries.toSet(),
        appLockEnabled = this[Keys.APP_LOCK_ENABLED] ?: false,
        waterTargetMl = this[Keys.WATER_TARGET_ML] ?: 2_000,
        waterServingMl = this[Keys.WATER_SERVING_ML] ?: 250,
        weeklySummaryEnabled = this[Keys.WEEKLY_SUMMARY_ENABLED] ?: false,
        weeklySummaryDay = this[Keys.WEEKLY_SUMMARY_DAY]
            ?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
            ?: DayOfWeek.SUNDAY,
        weeklySummaryHour = this[Keys.WEEKLY_SUMMARY_HOUR] ?: 19,
        activeProfileId = this[Keys.ACTIVE_PROFILE_ID] ?: 1L,
        legacyReminderAdopted = this[Keys.LEGACY_REMINDER_ADOPTED] ?: false,
        legacyDemographicsAdopted = this[Keys.LEGACY_DEMOGRAPHICS_ADOPTED] ?: false,
        nutritionEnabled = this[Keys.NUTRITION_ENABLED] ?: false,
        usdaApiKey = this[Keys.USDA_API_KEY]?.let(secrets::reveal),
        importLowestOfDay = this[Keys.IMPORT_LOWEST_OF_DAY] ?: false,
        scaleAddress = this[Keys.SCALE_ADDRESS],
        scaleName = this[Keys.SCALE_NAME],
    )

    private fun <T : Enum<T>> enumOrDefault(raw: String?, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback

    suspend fun setActiveProfile(id: Long) = edit { it[Keys.ACTIVE_PROFILE_ID] = id }

    suspend fun setLegacyReminderAdopted() = edit { it[Keys.LEGACY_REMINDER_ADOPTED] = true }

    suspend fun setLegacyDemographicsAdopted() = edit {
        it[Keys.LEGACY_DEMOGRAPHICS_ADOPTED] = true
    }

    suspend fun setNutritionEnabled(enabled: Boolean) = edit { it[Keys.NUTRITION_ENABLED] = enabled }

    suspend fun setImportLowestOfDay(only: Boolean) = edit {
        it[Keys.IMPORT_LOWEST_OF_DAY] = only
    }

    /**
     * Stores somebody's own USDA key, or refuses to.
     *
     * False means the phone would not give a key to encrypt it with, and nothing was written. It
     * is somebody's own quota on a public service, so it gets the same care as a password: the
     * old fallback wrote it in the clear and said nothing.
     */
    suspend fun setUsdaApiKey(key: String?): Boolean {
        if (key == null) {
            edit { it.remove(Keys.USDA_API_KEY) }
            return true
        }
        val protected = secrets.protect(key) ?: return false
        edit { it[Keys.USDA_API_KEY] = protected }
        return true
    }

    /** Remembers a scale, or forgets it when the address is null. */
    suspend fun setScale(address: String?, name: String?) = edit {
        if (address == null) {
            it.remove(Keys.SCALE_ADDRESS)
            it.remove(Keys.SCALE_NAME)
        } else {
            it[Keys.SCALE_ADDRESS] = address
            if (name.isNullOrBlank()) it.remove(Keys.SCALE_NAME) else it[Keys.SCALE_NAME] = name
        }
    }

    private object Keys {
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
        val SETTINGS_UPDATED_AT = longPreferencesKey("settings_updated_at")
        val LENGTH_UNIT = stringPreferencesKey("length_unit")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val HEIGHT_MM = intPreferencesKey("height_mm")
        val SEX = stringPreferencesKey("sex")
        val BIRTH_YEAR = intPreferencesKey("birth_year")
        val ACTIVITY_LEVEL = stringPreferencesKey("activity_level")
        val TREND_WINDOW_DAYS = intPreferencesKey("trend_window_days")
        val MILESTONE_STEP_GRAMS = intPreferencesKey("milestone_step_grams")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val REMINDER_HOUR = intPreferencesKey("reminder_hour")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        val REMINDER_DAYS = stringSetPreferencesKey("reminder_days")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val WATER_TARGET_ML = intPreferencesKey("water_target_ml")
        val WATER_SERVING_ML = intPreferencesKey("water_serving_ml")
        val WEEKLY_SUMMARY_ENABLED = booleanPreferencesKey("weekly_summary_enabled")
        val WEEKLY_SUMMARY_DAY = stringPreferencesKey("weekly_summary_day")
        val WEEKLY_SUMMARY_HOUR = intPreferencesKey("weekly_summary_hour")
        val ACTIVE_PROFILE_ID = longPreferencesKey("active_profile_id")
        val LEGACY_REMINDER_ADOPTED = booleanPreferencesKey("legacy_reminder_adopted")
        val LEGACY_DEMOGRAPHICS_ADOPTED =
            booleanPreferencesKey("legacy_demographics_adopted")
        val NUTRITION_ENABLED = booleanPreferencesKey("nutrition_enabled")
        val USDA_API_KEY = stringPreferencesKey("usda_api_key")
        val IMPORT_LOWEST_OF_DAY = booleanPreferencesKey("import_lowest_of_day")
        /**
         * Where Health Connect got to last time, per profile.
         *
         * Kept per profile because only one person's readings are exchanged and a household
         * that moves the claim would otherwise carry the other person's place in the queue.
         */
        fun healthChangesToken(profileId: Long) =
            stringPreferencesKey("health_changes_token_$profileId")

        val HEALTH_CONNECT_DECIDED = booleanPreferencesKey("health_connect_decided")

        fun healthImportedThrough(profileId: Long) =
            longPreferencesKey("health_imported_through_$profileId")

        fun healthBodyFatExported(profileId: Long) =
            booleanPreferencesKey("health_body_fat_exported_$profileId")

        fun healthDeletionsSent(profileId: Long) =
            stringSetPreferencesKey("health_deletions_sent_$profileId")

        val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
        val LAST_AUTO_BACKUP = longPreferencesKey("last_auto_backup")
        val AUTO_BACKUP_PROBLEM = booleanPreferencesKey("auto_backup_problem")

        val SCALE_ADDRESS = stringPreferencesKey("scale_address")
        val SCALE_NAME = stringPreferencesKey("scale_name")
    }
}
