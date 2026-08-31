package com.weighttrack.domain

import com.weighttrack.core.math.BodyMetrics
import com.weighttrack.core.math.DailyWeight
import com.weighttrack.core.math.GoalProjector
import com.weighttrack.core.math.Milestones
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.model.BodyMeasurement
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.GoalRepository
import com.weighttrack.data.repo.MeasurementRepository
import com.weighttrack.data.repo.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the stored readings into every figure the app displays.
 *
 * This is deliberately the only place the maths is wired together. A screen that wants the
 * trend, the projection and the BMI gets one consistent object rather than recomputing pieces
 * that could drift apart.
 */
@Singleton
class ProgressCalculator @Inject constructor(
    private val weightRepository: WeightRepository,
    private val goalRepository: GoalRepository,
    private val measurementRepository: MeasurementRepository,
    private val settingsRepository: SettingsRepository,
    private val profileRepository: com.weighttrack.data.repo.ProfileRepository,
) {
    fun observe(today: () -> LocalDate = { LocalDate.now() }): Flow<ProgressSnapshot> = combine(
        weightRepository.observeDailyWeights(),
        weightRepository.observeLatest(),
        goalRepository.observeActive(),
        measurementRepository.observeLatestPerType(),
        settingsRepository.settings,
        // Whose body the figures are worked out from. It comes off the profile rather than the
        // app's settings: a household sharing one phone shared one height, and switching person
        // computed their BMI, healthy range, body fat, basal rate and expenditure from the other
        // person's body without anything on screen suggesting it.
        profileRepository.activeProfile,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        build(
            daily = values[0] as List<DailyWeight>,
            latestEntry = values[1] as WeightEntry?,
            goal = values[2] as Goal?,
            measurements = values[3] as Map<MeasurementType, BodyMeasurement>,
            settings = values[4] as AppSettings,
            demographics = (values[5] as com.weighttrack.data.repo.Profile?)?.demographics
                ?: com.weighttrack.core.model.UserProfile(),
            today = today(),
        )
    }

    fun build(
        daily: List<DailyWeight>,
        latestEntry: WeightEntry?,
        goal: Goal?,
        measurements: Map<MeasurementType, BodyMeasurement>,
        settings: AppSettings,
        /**
         * The body to work the figures out from.
         *
         * Handed in rather than taken off the settings, because it belongs to one person and the
         * settings belong to the phone.
         */
        demographics: com.weighttrack.core.model.UserProfile = settings.profile,
        today: LocalDate,
    ): ProgressSnapshot {
        if (daily.isEmpty()) return ProgressSnapshot.empty(settings)

        val series = TrendEngine.computeSeries(daily, settings.trendWindowDays)
        val rate = TrendEngine.rate(series)
        val trendGrams = series.latestTrendGrams

        val projection = goal?.let {
            GoalProjector.project(
                it.direction,
                it.startGrams,
                it.targetGrams,
                series,
                rate,
                bandGrams = it.bandGrams,
            )
        }
        val milestones = goal
            ?.takeIf { it.direction != GoalDirection.MAINTAIN }
            ?.let { active ->
                val step = active.milestoneStepGrams.takeIf { it > 0 }
                    ?: Milestones.defaultStepGrams(settings.weightUnit)
                Milestones.withProgress(
                    Milestones.generate(active.startGrams, active.targetGrams, step),
                    series,
                    losing = active.direction == GoalDirection.LOSE,
                )
            }
            .orEmpty()

        val profile = demographics
        val currentGrams = trendGrams?.toInt() ?: latestEntry?.grams
        val heightMm = profile.heightMm.takeIf { it > 0 }

        val bmi = if (currentGrams != null && heightMm != null) {
            BodyMetrics.bmi(currentGrams, heightMm)
        } else {
            null
        }

        val composition = bodyComposition(latestEntry, measurements, profile.sex, heightMm, currentGrams)

        // Katch-McArdle is the better formula once lean mass is known, and it needs neither
        // age nor sex, so it also covers a profile that has not filled those in.
        val bmr = composition?.leanMassGrams?.let { BodyMetrics.basalMetabolicRateFromLeanMass(it) }
            ?: if (currentGrams != null && heightMm != null && profile.birthYear > 0) {
                BodyMetrics.basalMetabolicRate(
                    currentGrams,
                    heightMm,
                    profile.ageYears(today),
                    profile.sex,
                )
            } else {
                null
            }

        val waistMm = measurements[MeasurementType.WAIST]?.valueMm
        val waistRatio = if (waistMm != null && heightMm != null) {
            BodyMetrics.waistToHeightRatio(waistMm, heightMm)
        } else {
            null
        }

        return ProgressSnapshot(
            settings = settings,
            entryCount = daily.size,
            latestEntry = latestEntry,
            series = series,
            rate = rate,
            isPlateau = TrendEngine.isPlateau(series, rate),
            goal = goal,
            projection = projection,
            milestones = milestones,
            nextMilestone = Milestones.next(milestones),
            bmi = bmi,
            bmiCategory = bmi?.let { BodyMetrics.bmiCategory(it) },
            healthyRangeGrams = heightMm?.let { BodyMetrics.healthyWeightRangeGrams(it) },
            basalMetabolicRate = bmr,
            totalDailyEnergyExpenditure = bmr?.let {
                BodyMetrics.totalDailyEnergyExpenditure(it, profile.activityLevel)
            },
            bodyComposition = composition,
            waistToHeightRatio = waistRatio,
            waistToHeightCategory = waistRatio?.let { BodyMetrics.waistToHeightCategory(it) },
        )
    }

    /**
     * A figure from a smart scale beats a tape measure estimate, so a logged percentage wins.
     * The Navy estimate fills in for everyone measuring themselves with a tape.
     */
    private fun bodyComposition(
        latestEntry: WeightEntry?,
        measurements: Map<MeasurementType, BodyMeasurement>,
        sex: com.weighttrack.core.model.Sex,
        heightMm: Int?,
        currentGrams: Int?,
    ): BodyComposition? {
        val logged = latestEntry?.bodyFatPercent
        val percent: Double
        val source: BodyFatSource
        if (logged != null && logged > 0) {
            percent = logged
            source = BodyFatSource.LOGGED
        } else {
            val neck = measurements[MeasurementType.NECK]?.valueMm
            val waist = measurements[MeasurementType.WAIST]?.valueMm
            val hips = measurements[MeasurementType.HIPS]?.valueMm
            if (heightMm == null || neck == null || waist == null) return null
            percent = BodyMetrics.navyBodyFatPercent(sex, heightMm, neck, waist, hips) ?: return null
            source = BodyFatSource.NAVY_ESTIMATE
        }
        return BodyComposition(
            percent = percent,
            source = source,
            fatMassGrams = currentGrams?.let { BodyMetrics.fatMassGrams(it, percent) },
            leanMassGrams = currentGrams?.let { BodyMetrics.leanMassGrams(it, percent) },
        )
    }
}
