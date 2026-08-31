package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.Meal
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.data.food.OfflineFoodStore
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testProfileRepository
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FoodLogRepositoryTest {

    private lateinit var database: WeightTrackDatabase
    private lateinit var profiles: ProfileRepository
    private lateinit var foods: FoodRepository
    private lateinit var log: FoodLogRepository

    private val today = LocalDate.of(2026, 8, 29)
    private val yesterday = today.minusDays(1)

    private val oats = Food(
        name = "Oats",
        per100g = Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 67.7, fatG = 6.5),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        val settings = testSettingsRepository()
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            DeletionRecorder(database, database.deletionDao(), database.syncDao()),
            database.weightEntryDao(),
        )
        foods = FoodRepository(
            database.foodDao(),
            OfflineFoodStore(ApplicationProvider.getApplicationContext()),
            DeletionRecorder(database, database.deletionDao(), database.syncDao()),
        )
        log = FoodLogRepository(
            database.foodLogDao(),
            profiles,
            DeletionRecorder(database, database.deletionDao(), database.syncDao()),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun storedOats(): Food = foods.byId(foods.add(oats))!!

    @Test
    fun `an amount of a food adds up to what was eaten`() = runTest {
        log.log(storedOats(), grams = 40.0, meal = Meal.BREAKFAST, date = today)

        val day = log.observeDay(today).first()
        assertThat(day.entries).hasSize(1)
        assertThat(day.total.kcal).isWithin(1e-6).of(151.6)
        assertThat(day.total.proteinG!!).isWithin(1e-6).of(5.28)
        assertThat(day.forMeal(Meal.BREAKFAST)).hasSize(1)
        assertThat(day.forMeal(Meal.DINNER)).isEmpty()
    }

    @Test
    fun `a label corrected later does not rewrite what a day added up to`() = runTest {
        val food = storedOats()
        log.log(food, grams = 100.0, meal = Meal.BREAKFAST, date = today)

        foods.update(food.copy(per100g = food.per100g.copy(kcal = 500.0)))

        // The nutrition is copied onto the row when it is logged. Reading it back through the
        // food would quietly change history every time a label was corrected.
        assertThat(log.observeDay(today).first().total.kcal).isWithin(1e-6).of(379.0)
    }

    @Test
    fun `deleting a food does not take a day's total with it`() = runTest {
        val food = storedOats()
        log.log(food, grams = 100.0, meal = Meal.LUNCH, date = today)

        foods.delete(food)

        val day = log.observeDay(today).first()
        assertThat(day.entries).hasSize(1)
        assertThat(day.total.kcal).isWithin(1e-6).of(379.0)
        assertThat(day.entries.single().name).isEqualTo("Oats")
    }

    @Test
    fun `a quick add is calories and nothing else`() = runTest {
        // A meal out has no barcode and no label. An app that refuses to record it leaves a
        // hole in the day, which is worse than an approximate number.
        log.quickAdd(kcal = 650.0, meal = Meal.DINNER, name = "Curry out", date = today)

        val entry = log.observeDay(today).first().entries.single()
        assertThat(entry.name).isEqualTo("Curry out")
        assertThat(entry.grams).isNull()
        assertThat(entry.nutrients.kcal).isWithin(1e-9).of(650.0)
        assertThat(entry.nutrients.proteinG).isNull()
    }

    @Test
    fun `what is known survives being totalled with what is not`() = runTest {
        log.log(storedOats(), grams = 100.0, meal = Meal.BREAKFAST, date = today)
        log.quickAdd(kcal = 650.0, meal = Meal.DINNER, date = today)

        val total = log.observeDay(today).first().total
        assertThat(total.kcal).isWithin(1e-6).of(1029.0)
        // The protein from the oats is still known, even though the curry's is not.
        assertThat(total.proteinG!!).isWithin(1e-6).of(13.2)
    }

    @Test
    fun `yesterday's breakfast can be had again without retyping it`() = runTest {
        val food = storedOats()
        log.log(food, grams = 40.0, meal = Meal.BREAKFAST, date = yesterday)
        log.log(food, grams = 200.0, meal = Meal.DINNER, date = yesterday)

        val copied = log.copyDay(from = yesterday, to = today, meal = Meal.BREAKFAST)

        assertThat(copied).hasSize(1)
        val day = log.observeDay(today).first()
        assertThat(day.entries).hasSize(1)
        assertThat(day.forMeal(Meal.BREAKFAST).single().grams!!).isWithin(1e-9).of(40.0)
        // Yesterday is untouched.
        assertThat(log.observeDay(yesterday).first().entries).hasSize(2)
    }

    @Test
    fun `copying a whole day brings every meal`() = runTest {
        val food = storedOats()
        log.log(food, grams = 40.0, meal = Meal.BREAKFAST, date = yesterday)
        log.log(food, grams = 200.0, meal = Meal.DINNER, date = yesterday)

        assertThat(log.copyDay(from = yesterday, to = today)).hasSize(2)
        assertThat(log.observeDay(today).first().entries).hasSize(2)
    }

    @Test
    fun `copying a day with nothing in it does nothing`() = runTest {
        assertThat(log.copyDay(from = yesterday, to = today)).isEmpty()
        assertThat(log.observeDay(today).first().isEmpty).isTrue()
    }

    @Test
    fun `two people keep their eating apart`() = runTest {
        profiles.ensureDefault()
        val me = profiles.activeId()
        log.log(storedOats(), grams = 100.0, meal = Meal.BREAKFAST, date = today)

        profiles.add("Sam")
        log.quickAdd(kcal = 500.0, meal = Meal.LUNCH, date = today)

        assertThat(log.observeDay(today).first().total.kcal).isWithin(1e-9).of(500.0)
        profiles.setActive(me)
        assertThat(log.observeDay(today).first().total.kcal).isWithin(1e-6).of(379.0)
    }

    @Test
    fun `a meal is offered by the clock so the common case needs no tap`() {
        assertThat(Meal.forHour(8)).isEqualTo(Meal.BREAKFAST)
        assertThat(Meal.forHour(13)).isEqualTo(Meal.LUNCH)
        assertThat(Meal.forHour(19)).isEqualTo(Meal.DINNER)
        assertThat(Meal.forHour(23)).isEqualTo(Meal.SNACK)
        assertThat(Meal.forHour(2)).isEqualTo(Meal.SNACK)
    }

    @Test
    fun `recent days are summed in the database, newest first`() = runTest {
        val food = storedOats()
        log.log(food, grams = 100.0, meal = Meal.BREAKFAST, date = yesterday)
        log.log(food, grams = 100.0, meal = Meal.LUNCH, date = today)
        log.log(food, grams = 100.0, meal = Meal.DINNER, date = today)

        val days = log.observeRecentDays().first()

        assertThat(days.map { it.date }).containsExactly(today, yesterday).inOrder()
        assertThat(days.first().nutrients.kcal).isWithin(1e-6).of(758.0)
    }
}
