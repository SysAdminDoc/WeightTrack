package com.weighttrack.domain

import com.weighttrack.core.math.BmiCategory
import com.weighttrack.core.math.GoalProjection
import com.weighttrack.core.math.Milestone
import com.weighttrack.core.math.TrendRate
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.math.WaistToHeightCategory
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.prefs.AppSettings

/** Where body fat came from, so the screen can label an estimate as an estimate. */
enum class BodyFatSource {
    LOGGED,
    NAVY_ESTIMATE,
}

data class BodyComposition(
    val percent: Double,
    val source: BodyFatSource,
    val fatMassGrams: Int?,
    val leanMassGrams: Int?,
)

/**
 * Everything the dashboard shows, computed once from the database, the goal and the settings.
 *
 * Assembling it in one place keeps the screens free of maths and means a figure can never
 * disagree with the chart beside it.
 */
data class ProgressSnapshot(
    val settings: AppSettings,
    val entryCount: Int,
    val latestEntry: WeightEntry?,
    val series: TrendSeries,
    val rate: TrendRate,
    val isPlateau: Boolean,
    val goal: Goal?,
    val projection: GoalProjection?,
    val milestones: List<Milestone>,
    val nextMilestone: Milestone?,
    val bmi: Double?,
    val bmiCategory: BmiCategory?,
    val healthyRangeGrams: IntRange?,
    val basalMetabolicRate: Double?,
    val totalDailyEnergyExpenditure: Double?,
    val bodyComposition: BodyComposition?,
    val waistToHeightRatio: Double?,
    val waistToHeightCategory: WaistToHeightCategory?,
) {
    val hasData: Boolean get() = entryCount > 0

    /** Trend weight if there is one, otherwise the raw reading, otherwise nothing to show. */
    val displayGrams: Int?
        get() = series.latestTrendGrams?.toInt() ?: latestEntry?.grams

    companion object {
        fun empty(settings: AppSettings): ProgressSnapshot = ProgressSnapshot(
            settings = settings,
            entryCount = 0,
            latestEntry = null,
            series = TrendSeries(emptyList(), 0.1),
            rate = TrendRate(0.0, 0.0, 0),
            isPlateau = false,
            goal = null,
            projection = null,
            milestones = emptyList(),
            nextMilestone = null,
            bmi = null,
            bmiCategory = null,
            healthyRangeGrams = null,
            basalMetabolicRate = null,
            totalDailyEnergyExpenditure = null,
            bodyComposition = null,
            waistToHeightRatio = null,
            waistToHeightCategory = null,
        )
    }
}
