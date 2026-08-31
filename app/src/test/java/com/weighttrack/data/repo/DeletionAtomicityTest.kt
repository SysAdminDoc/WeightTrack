package com.weighttrack.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.DeletionDao
import com.weighttrack.data.db.DeletionEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.testSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.weighttrack.core.nutrition.MacroTarget
import com.weighttrack.data.food.OfflineFoodStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * The row and its tombstone, or neither.
 *
 * Every delete path used to take the row out and write the tombstone as two separate commits.
 * The gap is small and it is real: process death in it leaves a row gone from this phone with
 * nothing to say so, and the other device hands it straight back on the next sync. A deletion
 * that comes home is the most irritating way for sync to be wrong.
 *
 * Every path here is driven with a recorder that cannot write a tombstone, and the row is
 * expected to survive. That is the only way to see the contract: with a working recorder both
 * writes land whether or not they are one commit.
 */
@RunWith(RobolectricTestRunner::class)
class DeletionAtomicityTest {

    private lateinit var database: WeightTrackDatabase

    /** A recorder whose tombstone write always fails, standing in for death between the two. */
    private lateinit var broken: DeletionRecorder
    private lateinit var working: DeletionRecorder

    private lateinit var profiles: ProfileRepository
    private lateinit var brokenProfiles: ProfileRepository

    private val at = Instant.ofEpochMilli(1_800_000_000_000)

    /** Refuses every tombstone. Everything else answers as the real one does. */
    private class RefusingDeletionDao(private val real: DeletionDao) : DeletionDao by real {
        override suspend fun record(deletion: DeletionEntity) = error("no room for a tombstone")
        override suspend fun recordAll(deletions: List<DeletionEntity>) =
            error("no room for a tombstone")
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WeightTrackDatabase::class.java,
        ).allowMainThreadQueries().build()
        working = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        broken = DeletionRecorder(
            database,
            RefusingDeletionDao(database.deletionDao()),
            database.syncDao(),
        )
        val settings = testSettingsRepository()
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            working,
            database.weightEntryDao(),
        )
        brokenProfiles = ProfileRepository(
            database.profileDao(),
            settings,
            broken,
            database.weightEntryDao(),
        )
    }

    /** The bundled shelf, which every food repository needs and none of this is about. */
    private fun shelf() = OfflineFoodStore(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = database.close()

    /** Runs a delete that cannot record, and insists it failed rather than half succeeded. */
    private suspend fun refused(block: suspend () -> Unit) {
        val result = runCatching { block() }
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `a weigh-in survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val weights = WeightRepository(database.weightEntryDao(), profiles, working)
        weights.add(grams = 80_000, timestamp = at)
        val entry = weights.observeEntries().first().single()
        val broken = WeightRepository(database.weightEntryDao(), profiles, broken)

        refused { broken.delete(entry) }

        assertThat(database.syncDao().weights()).hasSize(1)
        assertThat(database.deletionDao().all()).isEmpty()
    }

    @Test
    fun `several weigh-ins survive a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val weights = WeightRepository(database.weightEntryDao(), profiles, working)
        weights.add(grams = 80_000, timestamp = at)
        weights.add(grams = 79_500, timestamp = at.minusMillis(86_400_000))
        val all = weights.observeEntries().first()
        val broken = WeightRepository(database.weightEntryDao(), profiles, broken)

        refused { broken.deleteByIds(all.map { it.id }) }

        assertThat(database.syncDao().weights()).hasSize(2)
    }

    @Test
    fun `a measurement survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val measurements = MeasurementRepository(database.measurementDao(), profiles, working)
        measurements.add(MeasurementType.WAIST, valueMm = 900, timestamp = at)
        val one = measurements.observeAll().first().single()
        val broken = MeasurementRepository(database.measurementDao(), profiles, broken)

        refused { broken.delete(one) }
        refused { broken.deleteByIds(listOf(one.id)) }

        assertThat(database.syncDao().measurements()).hasSize(1)
    }

    @Test
    fun `a drink survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val water = WaterRepository(database.waterDao(), profiles, working)
        water.add(millilitres = 250, timestamp = at)
        val one = water.observeForDate(at.atZone(java.time.ZoneOffset.UTC).toLocalDate())
            .first()
            .single()
        val broken = WaterRepository(database.waterDao(), profiles, broken)

        refused { broken.delete(one) }
        refused { broken.clearDate(one.localDate) }

        assertThat(database.syncDao().water()).hasSize(1)
    }

    @Test
    fun `a fast survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val fasts = FastRepository(database.fastDao(), profiles, working)
        fasts.start(targetMinutes = 960)
        val broken = FastRepository(database.fastDao(), profiles, broken)

        refused { broken.cancelActive() }

        assertThat(database.syncDao().fasts()).hasSize(1)
    }

    @Test
    fun `a goal survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val goals = GoalRepository(database.goalDao(), profiles, working)
        goals.setGoal(
            startGrams = 84_000,
            targetGrams = 76_000,
            milestoneStepGrams = 2_000,
            startDate = LocalDate.of(2026, 6, 1),
        )
        val goal = goals.observeAll().first().single()
        val broken = GoalRepository(database.goalDao(), profiles, broken)

        refused { broken.delete(goal) }

        assertThat(database.syncDao().goals()).hasSize(1)
    }

    @Test
    fun `a macro target survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val targets = MacroTargetRepository(database.macroTargetDao(), profiles, working)
        targets.set(MacroTarget(kcal = 2_100.0))
        targets.set(MacroTarget(kcal = 2_400.0), day = DayOfWeek.SUNDAY)
        val broken = MacroTargetRepository(database.macroTargetDao(), profiles, broken)

        refused { broken.clear(DayOfWeek.SUNDAY) }
        refused { broken.clearAll() }

        assertThat(database.syncDao().macroTargets()).hasSize(2)
    }

    @Test
    fun `a food and a recipe survive a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val foods = FoodRepository(database.foodDao(), shelf(), working)
        val id = foods.add(
            Food(
                name = "Oats",
                per100g = Nutrients(kcal = 370.0),
                origin = FoodOrigin.CUSTOM,
            ),
        )
        val food = checkNotNull(foods.byId(id))
        foods.saveRecipe("Porridge", servings = 2, items = listOf(RecipeItem(food, grams = 80.0)))
        val recipe = foods.observeRecipes().first().single()
        val broken = FoodRepository(database.foodDao(), shelf(), broken)

        refused { broken.deleteRecipe(recipe) }
        refused { broken.delete(food) }

        assertThat(database.syncDao().foods()).hasSize(1)
        assertThat(database.syncDao().recipes()).hasSize(1)
        assertThat(database.syncDao().recipeItems()).hasSize(1)
    }

    @Test
    fun `a diary entry survives a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val log = FoodLogRepository(database.foodLogDao(), profiles, working)
        val day = LocalDate.of(2026, 8, 29)
        log.quickAdd(kcal = 148.0, meal = com.weighttrack.core.nutrition.Meal.BREAKFAST, name = "Oats", date = day)
        val entry = log.observeDay(day).first().entries.single()
        val broken = FoodLogRepository(database.foodLogDao(), profiles, broken)

        refused { broken.delete(entry) }
        refused { broken.clearDay(day) }

        assertThat(database.syncDao().foodLog()).hasSize(1)
    }

    @Test
    fun `a profile and everything it owns survive a tombstone that cannot be written`() = runTest {
        profiles.ensureDefault()
        val id = profiles.add("Them")
        profiles.setActive(id)
        val weights = WeightRepository(database.weightEntryDao(), profiles, working)
        weights.add(grams = 62_000, timestamp = at)

        refused { brokenProfiles.delete(id) }

        // A half-deleted profile is the worst of the lot: the rows go, the tombstones do not,
        // and the other phone puts the whole person back.
        assertThat(database.syncDao().profiles()).hasSize(2)
        assertThat(database.syncDao().weights()).hasSize(1)
    }

    @Test
    fun `editing a recipe keeps its ingredients when the tombstone cannot be written`() = runTest {
        profiles.ensureDefault()
        val foods = FoodRepository(database.foodDao(), shelf(), working)
        val oats = checkNotNull(
            foods.byId(
                foods.add(
                    Food(
                        name = "Oats",
                        per100g = Nutrients(kcal = 370.0),
                        origin = FoodOrigin.CUSTOM,
                    ),
                ),
            ),
        )
        val id = foods.saveRecipe("Porridge", servings = 2, items = listOf(RecipeItem(oats, 80.0)))
        // The tombstone goes in first here, so the failure has to come after it: a database that
        // will not take the replacement rows, which is the shape process death has.
        val breaking = FoodRepository(
            object : com.weighttrack.data.db.FoodDao by database.foodDao() {
                override suspend fun replaceRecipeItems(
                    recipeId: Long,
                    items: List<com.weighttrack.data.db.RecipeItemEntity>,
                ) = error("the ingredients would not go in")
            },
            shelf(),
            working,
        )

        // Editing a recipe deletes the ingredient rows it had, so it is a delete path too, and
        // the one that was missed. An ingredient carries no time of its own, so a tombstone with
        // no replacement row always outranks it: the recipe empties itself here and then on
        // every other device, with nothing said about it anywhere.
        refused { breaking.saveRecipe("Porridge", servings = 2, items = emptyList(), id = id) }

        assertThat(database.syncDao().recipeItems()).hasSize(1)
        assertThat(database.deletionDao().all()).isEmpty()
    }

    @Test
    fun `deleting a profile remembers the days it ate as well`() = runTest {
        profiles.ensureDefault()
        val id = profiles.add("Them")
        profiles.setActive(id)
        val foods = FoodRepository(database.foodDao(), shelf(), working)
        val log = FoodLogRepository(database.foodLogDao(), profiles, working)
        log.quickAdd(
            kcal = 900.0,
            meal = com.weighttrack.core.nutrition.Meal.DINNER,
            name = "Takeaway",
            date = LocalDate.of(2026, 8, 29),
        )
        val gone = database.syncDao().foodLog().single().syncId

        profiles.delete(id)

        // Without this the other device goes on offering a deleted person's meals, and every
        // sync afterwards reports them as records with nowhere to belong.
        val tombstones = database.deletionDao().all()
            .filter { it.kind == SyncKind.FOOD_LOG.name }
            .map { it.syncId }
        assertThat(tombstones).containsExactly(gone)
    }

    @Test
    fun `every kind that can be deleted is covered here`() = runTest {
        // Named rather than counted, so adding a kind fails this until a case is written for it.
        val covered = setOf(
            SyncKind.WEIGHT,
            SyncKind.MEASUREMENT,
            SyncKind.WATER,
            SyncKind.FAST,
            SyncKind.GOAL,
            SyncKind.MACRO_TARGET,
            SyncKind.FOOD,
            SyncKind.RECIPE,
            SyncKind.RECIPE_ITEM,
            SyncKind.FOOD_LOG,
            SyncKind.PROFILE,
        )

        assertThat(covered).containsExactlyElementsIn(SyncKind.entries)
    }
}
