package com.weighttrack.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun setWeightUnit(unit: WeightUnit) = edit { it[Keys.WEIGHT_UNIT] = unit.name }

    suspend fun setLengthUnit(unit: LengthUnit) = edit { it[Keys.LENGTH_UNIT] = unit.name }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setProfile(profile: UserProfile) = edit {
        it[Keys.HEIGHT_MM] = profile.heightMm
        it[Keys.SEX] = profile.sex.name
        it[Keys.BIRTH_YEAR] = profile.birthYear
        it[Keys.ACTIVITY_LEVEL] = profile.activityLevel.name
    }

    suspend fun setHeightMm(heightMm: Int) = edit { it[Keys.HEIGHT_MM] = heightMm }

    suspend fun setTrendWindowDays(days: Int) = edit {
        it[Keys.TREND_WINDOW_DAYS] = days.coerceIn(TrendEngine.MIN_WINDOW_DAYS, TrendEngine.MAX_WINDOW_DAYS)
    }

    suspend fun setMilestoneStepGrams(grams: Int) = edit { it[Keys.MILESTONE_STEP_GRAMS] = grams }

    suspend fun setOnboardingComplete(complete: Boolean) = edit { it[Keys.ONBOARDING_COMPLETE] = complete }

    suspend fun setReminder(enabled: Boolean, hour: Int, minute: Int, days: Set<DayOfWeek>) = edit {
        it[Keys.REMINDER_ENABLED] = enabled
        it[Keys.REMINDER_HOUR] = hour.coerceIn(0, 23)
        it[Keys.REMINDER_MINUTE] = minute.coerceIn(0, 59)
        it[Keys.REMINDER_DAYS] = days.map { day -> day.name }.toSet()
    }

    suspend fun setReminderEnabled(enabled: Boolean) = edit { it[Keys.REMINDER_ENABLED] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
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
    )

    private fun <T : Enum<T>> enumOrDefault(raw: String?, values: List<T>, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback

    private object Keys {
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
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
    }
}
