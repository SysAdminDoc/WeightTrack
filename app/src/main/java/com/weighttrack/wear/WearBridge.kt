package com.weighttrack.wear

import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.core.sync.WearSummary
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.WeightRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Sends the current figures to a paired watch.
 *
 * The Play flavour talks to the Wear Data Layer; the F-Droid flavour has no Google dependency
 * and does nothing at all. Everything above this line is the same in both builds.
 */
interface WearBridge {
    /** Publishes the summary, replacing whatever the watch had. Quiet when there is no watch. */
    suspend fun publish(summary: WearSummary)

    /** True when this build can talk to a watch at all. */
    val isSupported: Boolean
}

/** The F-Droid build, and any device without the Data Layer. */
@Singleton
class NoWearBridge @Inject constructor() : WearBridge {
    override suspend fun publish(summary: WearSummary) = Unit
    override val isSupported: Boolean = false
}

/**
 * Reads the figures the watch needs.
 *
 * Kept away from the Data Layer plumbing so what the watch is told can be tested without a
 * paired device, and so both flavours build it the same way.
 */
@Singleton
class WearSummaryBuilder @Inject constructor(
    private val weightRepository: WeightRepository,
    private val goalRepository: GoalRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun current(): WearSummary {
        val settings = settingsRepository.settings.first()
        if (settings.appLockEnabled) return locked(settings)
        val daily = weightRepository.observeDailyWeights().first()
        val latest = weightRepository.latest()
        val goal = goalRepository.active()
        return build(
            settings = settings,
            series = if (daily.isEmpty()) null else TrendEngine.computeSeries(daily, settings.trendWindowDays, settings.smoothingMode),
            latest = latest,
            goalGrams = goal?.targetGrams,
            entryCount = weightRepository.count(),
        )
    }

    companion object {
        /**
         * With the app lock on the watch is told the unit and nothing else.
         *
         * A weight on a wrist is exactly what the lock exists to keep off a glanceable surface,
         * so the tile and the complication have nothing to draw.
         */
        fun locked(settings: AppSettings): WearSummary =
            WearSummary(weightUnit = settings.weightUnit, hidden = true)

        fun build(
            settings: AppSettings,
            series: com.weighttrack.core.math.TrendSeries?,
            latest: WeightEntry?,
            goalGrams: Int?,
            entryCount: Int,
            today: java.time.LocalDate = java.time.LocalDate.now(),
        ): WearSummary {
            if (settings.appLockEnabled) return locked(settings)
            return WearSummary(
                trendGrams = series?.latestTrendGrams?.roundToInt(),
                latestGrams = latest?.grams,
                // The same week the phone shows. See TrendHeroCard.
                weekChangeGrams = series?.let {
                    com.weighttrack.core.math.Analytics.changeSinceWeekStart(
                        it,
                        settings.weekRule,
                        today,
                    )
                },
                goalGrams = goalGrams,
                weightUnit = settings.weightUnit,
                lastLoggedEpochDay = series?.lastMeasured?.date?.toEpochDay(),
                entryCount = entryCount,
            )
        }
    }
}
