package com.weighttrack.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.R
import com.weighttrack.core.model.Goal
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.math.AdaptiveExpenditure
import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import com.weighttrack.core.nutrition.MacroTargets
import com.weighttrack.core.nutrition.Meal
import com.weighttrack.data.repo.DayLog
import com.weighttrack.data.repo.FoodLogEntry
import com.weighttrack.data.repo.FoodLogRepository
import com.weighttrack.data.repo.FoodRepository
import com.weighttrack.data.repo.MacroTargetRepository
import com.weighttrack.domain.ProgressCalculator
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.ui.AppStrings
import com.weighttrack.health.HealthConnectSync
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class DiaryUiState(
    val date: LocalDate = LocalDate.now(),
    val day: DayLog = DayLog(LocalDate.now(), emptyList()),
    /**
     * What to offer first when adding.
     *
     * Favourites, then what was eaten most recently, then the rest. After the first week the
     * top of this list is the answer almost every time.
     */
    val suggestions: List<Food> = emptyList(),
    val searchResults: List<Food> = emptyList(),
    val query: String = "",
    val message: String? = null,
    /** What this day is meant to come to, when there is a target at all. */
    val target: MacroTarget? = null,
    /**
     * Whether that target belongs to this day of the week alone.
     *
     * The difference decides where a change goes. Writing a Saturday target into the everyday
     * row would quietly replace the one the other six days were using, and leave Saturday
     * looking exactly as it did.
     */
    val targetIsForThisDay: Boolean = false,
    /**
     * What this person burns, worked out from what they ate and what their weight did.
     *
     * Null until there is enough of both to say anything, which is most of the first fortnight.
     */
    val expenditure: AdaptiveExpenditure.Estimate? = null,
    /** What to eat to keep moving at the rate their goal implies. */
    val recommendation: AdaptiveExpenditure.Recommendation? = null,
    /**
     * Whether the estimate is steady enough to state rather than hedge.
     *
     * A fortnight with food logged on nine of its days, or a weight that moved five kilograms,
     * is an arithmetic result rather than a fact about somebody's metabolism.
     */
    val expenditureConfident: Boolean = false,
) {
    val isToday: Boolean get() = date == LocalDate.now()

    /** What is left of the day, which is the number people actually look at. */
    val remaining get() = target?.remaining(day.total)
}

/**
 * A day's eating.
 *
 * The day is state rather than read from the clock each time, so the screen can be walked back
 * through the week, and so a phone left open past midnight does not silently start adding
 * today's breakfast to yesterday.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val strings: AppStrings,
    private val foodLogRepository: FoodLogRepository,
    private val foodRepository: FoodRepository,
    private val macroTargetRepository: MacroTargetRepository,
    private val progressCalculator: ProgressCalculator,
    private val goalRepository: com.weighttrack.data.repo.GoalRepository,
    private val healthConnect: HealthConnectSync,
    private val undoOffers: com.weighttrack.ui.UndoCoordinator,
) : ViewModel() {

    /**
     * Daily step counts, when Health Connect has them and has been allowed to share them.
     *
     * Read once when the screen opens. Never turned into calories: all it decides is whether the
     * days in the window are evidence about what this person burns this week.
     */
    private val steps = MutableStateFlow<Map<LocalDate, Long>>(emptyMap())

    init {
        viewModelScope.launch {
            val read = runCatching { healthConnect.readDailyActivity(days = STEP_HISTORY_DAYS) }
                .getOrNull()
            if (read is com.weighttrack.health.HealthOutcome.Ok) {
                steps.value = read.value.mapNotNull { day -> day.steps?.let { day.date to it } }
                    .toMap()
            }
        }
    }

    private val date = MutableStateFlow(LocalDate.now())
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<DiaryUiState> = combine(
        combine(date, macroTargetRepository.observe()) { date, targets -> date to targets },
        date.flatMapLatest { foodLogRepository.observeDay(it) },
        // Every food, already ordered favourites first and then by what was eaten most
        // recently. Offering only the recents means the first food somebody adds can never be
        // logged, because nothing has been eaten yet.
        foodRepository.search("", SUGGESTION_LIMIT),
        query.flatMapLatest { foodRepository.search(it) },
        combine(
            message,
            progressCalculator.observe(),
            foodLogRepository.observeRecentDays(),
            steps,
            goalRepository.observeAll(),
        ) { message, snapshot, intake, steps, goals ->
            // Worked out here rather than in the screen, so the one place the maths is wired
            // together stays the one place.
            val estimate = AdaptiveExpenditure.estimate(
                series = snapshot.series,
                intakeByDate = intake.associate { it.date to it.nutrients.kcal },
                today = LocalDate.now(),
                stepsByDate = steps,
            )?.let { measured -> afterAnyGoalChange(measured, snapshot, goals) }
            val goal = snapshot.goal
            val recommendation = estimate?.let {
                AdaptiveExpenditure.recommendedIntake(
                    it,
                    AdaptiveExpenditure.rateForGoal(
                        currentGrams = snapshot.displayGrams,
                        targetGrams = goal?.targetGrams,
                        weeks = DEFAULT_GOAL_WEEKS,
                    ),
                )
            }
            Insight(message, estimate, recommendation)
        },
    ) { dateAndTargets, day, recent, results, insight ->
        val (date, targets) = dateAndTargets
        val message = insight.message
        DiaryUiState(
            date = date,
            target = targets.forDay(date.dayOfWeek),
            targetIsForThisDay = targets.byDay.containsKey(date.dayOfWeek),
            day = day,
            suggestions = recent,
            searchResults = if (query.value.isBlank()) emptyList() else results,
            query = query.value,
            message = message,
            expenditure = insight.expenditure,
            recommendation = insight.recommendation,
            expenditureConfident = insight.expenditure
                ?.let { AdaptiveExpenditure.isConfident(it) } == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    fun showPreviousDay() {
        date.value = date.value.minusDays(1)
    }

    fun showNextDay() {
        // Nothing has been eaten tomorrow.
        if (date.value < LocalDate.now()) date.value = date.value.plusDays(1)
    }

    fun setQuery(text: String) {
        query.value = text
    }

    /** The meal to offer first, given the hour, so the common case needs no tap. */
    fun suggestedMeal(): Meal = Meal.forHour(LocalTime.now().hour)

    fun log(food: Food, grams: Double, meal: Meal) {
        if (grams <= 0) return
        viewModelScope.launch {
            val id = foodLogRepository.log(food, grams, meal, date.value)
            // Straight to the top of the suggestions, which is where it will be wanted again.
            foodRepository.markUsed(food.id)
            query.value = ""
            shareWithHealthConnect(id)
        }
    }

    fun quickAdd(kcal: Double, meal: Meal, name: String) {
        if (kcal <= 0) return
        viewModelScope.launch {
            val id = foodLogRepository.quickAdd(kcal, meal, name, date.value)
            shareWithHealthConnect(id)
        }
    }

    /**
     * Passes a meal on to Health Connect, when that has been allowed.
     *
     * Quiet either way. Nothing about logging food should depend on another app being installed,
     * and a refused permission is a choice rather than a failure to report.
     */
    private suspend fun shareWithHealthConnect(entryId: Long) {
        val entry = foodLogRepository.day(date.value).entries.firstOrNull { it.id == entryId }
            ?: return
        healthConnect.writeNutrition(
            // The day the meal belongs to, at the time of day it was entered. Using the moment
            // of entry would file Tuesday's dinner, added on Thursday, under Thursday, and every
            // other app reading Health Connect would then disagree with the diary about both
            // days. Copying yesterday made that the normal case rather than the awkward one.
            instant = HealthConnectSync.instantFor(entry.date, entry.loggedAtUtcMillis),
            kcal = entry.nutrients.kcal,
            proteinG = entry.nutrients.proteinG,
            carbsG = entry.nutrients.carbsG,
            fatG = entry.nutrients.fatG,
            name = entry.name,
            mealType = HealthConnectSync.mealTypeFor(entry.meal),
            clientRecordId = HealthConnectSync.nutritionRecordId(entryId),
        )
    }

    /** People eat the same breakfast for months, and retyping it is what stops them logging. */
    fun copyYesterday(meal: Meal? = null) {
        viewModelScope.launch {
            val copied = foodLogRepository.copyDay(date.value.minusDays(1), date.value, meal)
            // Copied food is food. Leaving it out made the diary and Health Connect disagree
            // about the same day for no reason anybody could have worked out.
            copied.forEach { shareWithHealthConnect(it) }
            message.value = when {
                copied.isEmpty() -> strings[R.string.diary_nothing_to_copy_from_the_day]
                meal == null -> strings[R.string.diary_copied_things_from_yesterday, copied.size]
                else -> strings[
                    R.string.diary_copied_yesterday_s,
                    strings[com.weighttrack.ui.format.Labels.of(meal)].lowercase(),
                ]
            }
        }
    }

    fun delete(entry: FoodLogEntry) {
        viewModelScope.launch {
            val removed = foodLogRepository.delete(entry)
            // Removed there too, or the two drift apart and the day reads differently in each.
            healthConnect.deleteNutrition(HealthConnectSync.nutritionRecordId(entry.id))
            // The row comes back under the id it had, so the same call that published it the
            // first time publishes it again rather than filing a second meal.
            undoOffers.offer(removed, strings[R.string.diary_entry_deleted]) {
                shareWithHealthConnect(entry.id)
            }
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    /**
     * Sets what a day should come to.
     *
     * Grams are stored whatever was typed. A share of a calorie figure that later changes would
     * silently mean something different, and grams are what a food is measured in.
     */
    fun setTarget(
        kcal: Double,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        basis: MacroBasis,
        day: java.time.DayOfWeek? = null,
    ) {
        if (kcal <= 0) return
        viewModelScope.launch {
            macroTargetRepository.set(
                MacroTarget(
                    kcal = kcal,
                    proteinG = proteinG,
                    carbsG = carbsG,
                    fatG = fatG,
                    basis = basis,
                ),
                day = day,
            )
            message.value = day
                ?.let {
                    strings[
                        R.string.diary_target_set_for_every_day,
                        it.name.lowercase().replaceFirstChar(Char::uppercase),
                    ]
                }
                ?: strings[R.string.diary_daily_target_set]
        }
    }

    fun clearTarget(day: java.time.DayOfWeek? = null) {
        viewModelScope.launch {
            macroTargetRepository.clear(day)
            message.value = if (day == null) {
                strings[R.string.diary_target_cleared]
            } else {
                strings[R.string.diary_that_day_is_back_on_the_everyday_target]
            }
        }
    }

    private data class Insight(
        val message: String?,
        val expenditure: AdaptiveExpenditure.Estimate?,
        val recommendation: AdaptiveExpenditure.Recommendation?,
    )

    /** Takes the recommendation as the target, which is the point of working it out. */
    fun useRecommendation() {
        val current = state.value
        val recommended = current.recommendation ?: return
        val day = TargetRevision.rowFor(current.date.dayOfWeek, current.targetIsForThisDay)
        val revised = TargetRevision.revised(current.target, recommended.kcalPerDay)
        viewModelScope.launch {
            macroTargetRepository.set(revised, day = day)
            message.value = if (day == null) {
                strings[R.string.diary_target_set_to_kcal, recommended.rounded]
            } else {
                strings[
                    R.string.diary_day_set_to,
                    com.weighttrack.ui.format.Labels.of(day),
                    recommended.rounded,
                ]
            }
        }
    }

    /** "Monday", not "MONDAY". */
    companion object {
        const val SUGGESTION_LIMIT = 12

        /**
         * How long a goal is assumed to run when working out a rate.
         *
         * A goal in this app carries a target and not a deadline, on purpose, so the rate has to
         * come from somewhere. Twelve weeks is a normal length for one and gives a rate nobody
         * needs talking out of.
         */
        const val DEFAULT_GOAL_WEEKS = 12.0

        /** Far enough back to cover any window the estimate might measure across. */
        const val STEP_HISTORY_DAYS = 30L

        /**
         * How close the retirement of one goal and the setting of the next have to be to count
         * as one replacing the other.
         *
         * They are written in the same transaction, so in practice they are milliseconds apart.
         * A minute is slack for a slow phone, and far short of anything that would let a goal
         * abandoned earlier be mistaken for the one this replaced.
         */
        const val REPLACEMENT_WINDOW_MILLIS = 60_000L

        /**
         * Moves the estimate the moment the target does, while the window still predates it.
         *
         * Expenditure falls in a deficit and comes back when the deficit stops, days before a
         * scale can show either. Handing back a number measured across the old target recommends
         * a fortnight of eating too little to somebody who has just decided to stop dieting. Once
         * the whole window sits after the change, the measurement already reflects it and the
         * correction goes away on its own.
         */
        internal fun afterAnyGoalChange(
            measured: AdaptiveExpenditure.Estimate,
            snapshot: ProgressSnapshot,
            goals: List<Goal>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): AdaptiveExpenditure.Estimate {
            // The active goal is taken from the same list as the retired one, not from the
            // snapshot, which arrives on its own flow. Two flows fed by the same table still
            // pair up one emission behind each other, and a new goal read beside the old list
            // is a correction worked out from a target nobody is on.
            val current = goals.firstOrNull { it.active } ?: return measured
            // When the target changed, which is not when the goal started. Editing a goal keeps
            // its start date so the progress bar does not reset, so reading the start date meant
            // the correction never fired on the one path that actually changes a target.
            if (current.setAtUtcMillis <= 0) return measured
            val changedOn = Instant.ofEpochMilli(current.setAtUtcMillis).atZone(zone).toLocalDate()
            if (!measured.from.isBefore(changedOn)) return measured
            // Ordered newest first, so the first retired one is what this goal replaced. Retired
            // at the moment this one was set, or it replaced nothing: somebody who gave a goal up
            // last year and sets a new one today has no suppressed expenditure to hand back, and
            // reading the abandoned goal's rate handed them four per cent they never lost.
            val previous = goals.firstOrNull { !it.active && it.id != current.id } ?: return measured
            val apart = abs(previous.changedAtUtcMillis - current.setAtUtcMillis)
            if (previous.changedAtUtcMillis <= 0 || apart > REPLACEMENT_WINDOW_MILLIS) return measured
            val bodyKg = (snapshot.displayGrams ?: return measured) / 1_000.0
            return AdaptiveExpenditure.afterGoalChange(
                measured,
                fromKgPerWeek = AdaptiveExpenditure.rateForGoal(
                    previous.startGrams,
                    previous.targetGrams,
                    DEFAULT_GOAL_WEEKS,
                ),
                toKgPerWeek = AdaptiveExpenditure.rateForGoal(
                    current.startGrams,
                    current.targetGrams,
                    DEFAULT_GOAL_WEEKS,
                ),
                bodyMassKg = bodyKg,
            )
        }
    }
}
