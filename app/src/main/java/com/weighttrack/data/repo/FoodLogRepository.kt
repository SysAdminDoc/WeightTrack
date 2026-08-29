package com.weighttrack.data.repo

import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.Meal
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.data.db.FoodLogDao
import com.weighttrack.data.db.FoodLogEntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One thing eaten. */
data class FoodLogEntry(
    val id: Long,
    val date: LocalDate,
    val meal: Meal,
    /** Null once the food it came from has been deleted, or for a quick-add. */
    val foodId: Long?,
    val name: String,
    /** Null for a quick-add, which is a number of calories and nothing else. */
    val grams: Double?,
    val nutrients: Nutrients,
    val loggedAtUtcMillis: Long,
)

/** A day, split the way people eat rather than as one long list. */
data class DayLog(
    val date: LocalDate,
    val entries: List<FoodLogEntry>,
) {
    val total: Nutrients
        get() = entries.fold(Nutrients.NONE) { running, entry -> running + entry.nutrients }

    fun forMeal(meal: Meal): List<FoodLogEntry> = entries.filter { it.meal == meal }

    fun totalFor(meal: Meal): Nutrients =
        forMeal(meal).fold(Nutrients.NONE) { running, entry -> running + entry.nutrients }

    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * What was eaten, per person per day.
 *
 * The nutrition is copied onto the row when it is logged rather than read back through the food.
 * A label corrected next month must not rewrite what last month's days added up to, and
 * deleting a food must not take a day's total with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FoodLogRepository @Inject constructor(
    private val dao: FoodLogDao,
    private val profiles: ProfileRepository,
) {
    fun observeDay(date: LocalDate): Flow<DayLog> =
        profiles.activeProfileId
            .flatMapLatest { dao.observeForDate(it, date.toString()) }
            .map { rows -> DayLog(date, rows.mapNotNull { it.toDomain() }) }

    fun observeRecentDays(days: Int = RECENT_DAYS): Flow<List<DailyIntake>> =
        profiles.activeProfileId
            .flatMapLatest { dao.observeRecentDays(it, days) }
            .map { rows ->
                rows.mapNotNull { row ->
                    val date = runCatching { LocalDate.parse(row.localDate) }.getOrNull()
                    date?.let {
                        DailyIntake(
                            it,
                            Nutrients(row.kcal, row.proteinG, row.carbsG, row.fatG),
                        )
                    }
                }
            }

    suspend fun day(date: LocalDate): DayLog =
        DayLog(date, dao.forDate(profiles.activeId(), date.toString()).mapNotNull { it.toDomain() })

    /** Logs an amount of a food. */
    suspend fun log(
        food: Food,
        grams: Double,
        meal: Meal,
        date: LocalDate = LocalDate.now(),
    ): Long {
        val nutrients = food.forGrams(grams)
        return dao.insert(
            FoodLogEntryEntity(
                profileId = profiles.activeId(),
                localDate = date.toString(),
                meal = meal.name,
                foodId = food.id.takeIf { it > 0 },
                name = food.label,
                grams = grams,
                kcal = nutrients.kcal,
                proteinG = nutrients.proteinG,
                carbsG = nutrients.carbsG,
                fatG = nutrients.fatG,
                loggedAtUtcMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Logs a number of calories and nothing else.
     *
     * The escape hatch that stops a food log becoming a chore. A meal out has no barcode and no
     * label, and an app that refuses to record it gets a day with a hole in it instead of an
     * approximate number, which is worse.
     */
    suspend fun quickAdd(
        kcal: Double,
        meal: Meal,
        name: String = "Quick add",
        date: LocalDate = LocalDate.now(),
    ): Long = dao.insert(
        FoodLogEntryEntity(
            profileId = profiles.activeId(),
            localDate = date.toString(),
            meal = meal.name,
            foodId = null,
            name = name.trim().ifBlank { "Quick add" },
            grams = null,
            kcal = kcal,
            proteinG = null,
            carbsG = null,
            fatG = null,
            loggedAtUtcMillis = System.currentTimeMillis(),
        ),
    )

    /**
     * Copies a day's eating onto another one.
     *
     * People eat the same breakfast for months. Retyping it every morning is the thing that
     * makes them stop logging.
     */
    /**
     * Copies a day's eating onto another one, handing back the rows it made.
     *
     * The identifiers are returned rather than a count because the caller has to pass each new
     * meal on to Health Connect. Copied food is food, and a day that appears in the diary but
     * not in Health Connect makes the two disagree for no reason anybody could work out.
     */
    suspend fun copyDay(from: LocalDate, to: LocalDate, meal: Meal? = null): List<Long> {
        val profileId = profiles.activeId()
        val source = dao.forDate(profileId, from.toString())
            .filter { meal == null || it.meal == meal.name }
        if (source.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return dao.insertAll(
            source.map { it.copy(id = 0, localDate = to.toString(), loggedAtUtcMillis = now) },
        )
    }

    suspend fun update(entry: FoodLogEntry) {
        val existing = dao.byId(entry.id) ?: return
        dao.update(
            existing.copy(
                meal = entry.meal.name,
                grams = entry.grams,
                kcal = entry.nutrients.kcal,
                proteinG = entry.nutrients.proteinG,
                carbsG = entry.nutrients.carbsG,
                fatG = entry.nutrients.fatG,
            ),
        )
    }

    suspend fun delete(entry: FoodLogEntry) {
        dao.byId(entry.id)?.let { dao.delete(it) }
    }

    suspend fun clearDay(date: LocalDate) =
        dao.deleteForDate(profiles.activeId(), date.toString())

    suspend fun deleteAll() = dao.deleteAll()

    private fun FoodLogEntryEntity.toDomain(): FoodLogEntry? {
        val date = runCatching { LocalDate.parse(localDate) }.getOrNull() ?: return null
        return FoodLogEntry(
            id = id,
            date = date,
            // An unknown meal name is filed under snacks rather than dropped: the calories were
            // eaten whatever the row calls them.
            meal = runCatching { Meal.valueOf(meal) }.getOrDefault(Meal.SNACK),
            foodId = foodId,
            name = name,
            grams = grams,
            nutrients = Nutrients(kcal, proteinG, carbsG, fatG),
            loggedAtUtcMillis = loggedAtUtcMillis,
        )
    }

    companion object {
        const val RECENT_DAYS = 30
    }
}

/** A day and what it came to. */
data class DailyIntake(val date: LocalDate, val nutrients: Nutrients)
