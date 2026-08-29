package com.weighttrack.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.Meal
import com.weighttrack.data.repo.DayLog
import com.weighttrack.data.repo.FoodLogEntry
import com.weighttrack.data.repo.FoodLogRepository
import com.weighttrack.data.repo.FoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
) {
    val isToday: Boolean get() = date == LocalDate.now()
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
    private val foodLogRepository: FoodLogRepository,
    private val foodRepository: FoodRepository,
) : ViewModel() {

    private val date = MutableStateFlow(LocalDate.now())
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<DiaryUiState> = combine(
        date,
        date.flatMapLatest { foodLogRepository.observeDay(it) },
        // Every food, already ordered favourites first and then by what was eaten most
        // recently. Offering only the recents means the first food somebody adds can never be
        // logged, because nothing has been eaten yet.
        foodRepository.search("", SUGGESTION_LIMIT),
        query.flatMapLatest { foodRepository.search(it) },
        message,
    ) { date, day, recent, results, message ->
        DiaryUiState(
            date = date,
            day = day,
            suggestions = recent,
            searchResults = if (query.value.isBlank()) emptyList() else results,
            query = query.value,
            message = message,
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
            foodLogRepository.log(food, grams, meal, date.value)
            // Straight to the top of the suggestions, which is where it will be wanted again.
            foodRepository.markUsed(food.id)
            query.value = ""
        }
    }

    fun quickAdd(kcal: Double, meal: Meal, name: String) {
        if (kcal <= 0) return
        viewModelScope.launch {
            foodLogRepository.quickAdd(kcal, meal, name, date.value)
        }
    }

    /** People eat the same breakfast for months, and retyping it is what stops them logging. */
    fun copyYesterday(meal: Meal? = null) {
        viewModelScope.launch {
            val copied = foodLogRepository.copyDay(date.value.minusDays(1), date.value, meal)
            message.value = when {
                copied == 0 -> "Nothing to copy from the day before."
                meal == null -> "Copied $copied things from yesterday."
                else -> "Copied yesterday's ${meal.label.lowercase()}."
            }
        }
    }

    fun delete(entry: FoodLogEntry) {
        viewModelScope.launch { foodLogRepository.delete(entry) }
    }

    fun dismissMessage() {
        message.value = null
    }

    companion object {
        const val SUGGESTION_LIMIT = 12
    }
}
