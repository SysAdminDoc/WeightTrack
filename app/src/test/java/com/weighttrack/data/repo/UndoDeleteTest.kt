package com.weighttrack.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.food.OfflineFoodStore
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.testSettingsRepository
import com.weighttrack.diagnostics.RuntimeLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Every destructive action, and whether it can be taken back.
 *
 * Two things have to hold for every one of them. The row comes back under the identity it had,
 * because a restore that mints a new name arrives on the other device as a second record rather
 * than as a correction. And the tombstone goes, because a restored row with its deletion still
 * standing is published as both, and the reading disappears again a sync later with nothing on
 * either phone to explain it.
 */
@RunWith(RobolectricTestRunner::class)
class UndoDeleteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: WeightTrackDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var deletions: DeletionRecorder
    private lateinit var profiles: ProfileRepository
    private lateinit var weights: WeightRepository
    private lateinit var water: WaterRepository
    private lateinit var fasts: FastRepository
    private lateinit var goals: GoalRepository
    private lateinit var measurements: MeasurementRepository
    private lateinit var foods: FoodRepository
    private lateinit var photos: ProgressPhotoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, WeightTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = testSettingsRepository()
        deletions = DeletionRecorder(database, database.deletionDao(), database.syncDao())
        profiles = ProfileRepository(
            database.profileDao(),
            settings,
            deletions,
            database.weightEntryDao(),
        )
        weights = WeightRepository(database.weightEntryDao(), profiles, deletions)
        water = WaterRepository(database.waterDao(), profiles, deletions)
        fasts = FastRepository(database.fastDao(), profiles, deletions)
        goals = GoalRepository(database.goalDao(), profiles, deletions)
        measurements = MeasurementRepository(database.measurementDao(), profiles, deletions)
        foods = FoodRepository(database.foodDao(), OfflineFoodStore(context), deletions)
        photos = ProgressPhotoRepository(
            context,
            database.progressPhotoDao(),
            profiles,
            RuntimeLog(File(context.cacheDir, "log.txt")),
        )
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, ProgressPhotoRepository.DIRECTORY_NAME).deleteRecursively()
        File(context.filesDir, ProgressPhotoRepository.RECOVERY_DIRECTORY_NAME).deleteRecursively()
    }

    private suspend fun tombstones(kind: SyncKind): List<String> =
        database.deletionDao().all().filter { it.kind == kind.name }.map { it.syncId }

    @Test
    fun `an undone reading comes back under its own name with no tombstone left`() = runTest {
        profiles.ensureDefault()
        weights.add(grams = 80_000, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        val entry = weights.observeEntries().first().single()

        val undo = weights.delete(entry)
        assertThat(weights.observeEntries().first()).isEmpty()
        assertThat(tombstones(SyncKind.WEIGHT)).containsExactly(entry.clientRecordId)

        undo!!.undo()

        val restored = weights.observeEntries().first().single()
        assertThat(restored.id).isEqualTo(entry.id)
        assertThat(restored.clientRecordId).isEqualTo(entry.clientRecordId)
        assertThat(tombstones(SyncKind.WEIGHT)).isEmpty()
    }

    @Test
    fun `undoing one person's deletion leaves another person's tombstone alone`() = runTest {
        // The CSV importer derives a reading's travelling name from its timestamp and weight, so
        // the same file imported for two people gives both identical names. An unscoped forget
        // would clear the other person's deletion and their phone would hand the row straight
        // back.
        profiles.ensureDefault()
        val first = profiles.activeId()
        val second = profiles.add("Someone else")
        // One name, two people: what the CSV importer produces when the same file is imported
        // for both, because it derives the name from the timestamp and the weight.
        val shared = "csv-1800000000000-80000"
        weights.addFor(
            first,
            grams = 80_000,
            timestamp = Instant.ofEpochMilli(1_800_000_000_000),
            clientRecordId = shared,
        )
        weights.addFor(
            second,
            grams = 80_000,
            timestamp = Instant.ofEpochMilli(1_800_000_000_000),
            clientRecordId = shared,
        )
        val all = database.syncDao().weights()
        assertThat(all).hasSize(2)
        assertThat(all.map { it.clientRecordId }.toSet()).containsExactly(shared)

        val theirs = all.single { it.profileId == second }
        weights.deleteByIds(listOf(theirs.id))
        val mine = all.single { it.profileId == first }
        val undo = weights.deleteByIds(listOf(mine.id))
        assertThat(tombstones(SyncKind.WEIGHT)).hasSize(2)

        undo!!.undo()

        assertThat(tombstones(SyncKind.WEIGHT)).containsExactly(theirs.clientRecordId)
    }

    @Test
    fun `a cleared water day comes back whole`() = runTest {
        profiles.ensureDefault()
        val today = LocalDate.of(2026, 8, 31)
        val morning = today.atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        water.add(millilitres = 250, timestamp = morning)
        water.add(millilitres = 330, timestamp = morning.plusSeconds(3_600))
        val before = water.observeForDate(today).first()
        assertThat(before).hasSize(2)

        val undo = water.clearDate(today)
        assertThat(water.observeForDate(today).first()).isEmpty()

        undo!!.undo()

        assertThat(water.observeForDate(today).first().map { it.id })
            .containsExactlyElementsIn(before.map { it.id })
        assertThat(tombstones(SyncKind.WATER)).isEmpty()
    }

    @Test
    fun `an undone recipe brings its ingredients with it`() = runTest {
        val oats = foods.add(
            Food(name = "Oats", per100g = Nutrients(kcal = 379.0, proteinG = 13.2, carbsG = 67.7, fatG = 6.5)),
        )
        val milk = foods.add(
            Food(name = "Milk", per100g = Nutrients(kcal = 47.0, proteinG = 3.4, carbsG = 4.8, fatG = 1.7)),
        )
        val id = foods.saveRecipe(
            name = "Porridge",
            servings = 2,
            items = listOf(
                RecipeItem(foods.byId(oats)!!, grams = 100.0),
                RecipeItem(foods.byId(milk)!!, grams = 300.0),
            ),
        )
        val recipe = foods.recipeById(id)!!

        val undo = foods.deleteRecipe(recipe)
        assertThat(foods.recipeById(id)).isNull()
        // The ingredient rows go with the recipe. Left behind they are invisible to every screen
        // and still published by sync as live rows alongside their own tombstones.
        assertThat(database.syncDao().recipeItems()).isEmpty()

        undo!!.undo()

        val restored = foods.recipeById(id)!!
        assertThat(restored.items.map { it.grams }).containsExactly(100.0, 300.0)
        assertThat(tombstones(SyncKind.RECIPE)).isEmpty()
        assertThat(tombstones(SyncKind.RECIPE_ITEM)).isEmpty()
    }

    @Test
    fun `an undone profile brings back the person and everything recorded for them`() = runTest {
        profiles.ensureDefault()
        val keep = profiles.activeId()
        val going = profiles.add("Someone else")
        weights.addFor(going, grams = 71_000, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        weights.addFor(going, grams = 70_500, timestamp = Instant.ofEpochMilli(1_800_086_400_000))
        goals.setGoal(startGrams = 71_000, targetGrams = 65_000, milestoneStepGrams = 1_000, owner = going)

        val deletion = profiles.deleteReturningPhotos(going)!!
        assertThat(profiles.observeAll().first().map { it.id }).containsExactly(keep)
        assertThat(database.syncDao().weights()).isEmpty()

        deletion.restore()

        assertThat(profiles.observeAll().first().map { it.id }).containsExactly(keep, going)
        assertThat(database.syncDao().weights().map { it.profileId }).containsExactly(going, going)
        assertThat(database.syncDao().goals()).hasSize(1)
        assertThat(database.deletionDao().all()).isEmpty()
    }

    @Test
    fun `an undone photo gets its file back, and a lapsed one does not`() = runTest {
        profiles.ensureDefault()
        val kept = photos.newCaptureFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        photos.record(kept, weightGrams = null, timestamp = Instant.ofEpochMilli(1_800_000_000_000))
        val photo = photos.observeAll().first().single()

        val undo = photos.delete(photo)!!
        assertThat(photo.file.exists()).isFalse()

        undo.undo()

        val restored = photos.observeAll().first().single()
        assertThat(restored.id).isEqualTo(photo.id)
        assertThat(restored.file.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))

        // And the other way: an offer nobody takes leaves nothing behind.
        val second = photos.delete(restored)!!
        second.lapse()
        assertThat(File(context.filesDir, ProgressPhotoRepository.RECOVERY_DIRECTORY_NAME).listFiles())
            .isEmpty()
        assertThat(photos.observeAll().first()).isEmpty()
    }

    @Test
    fun `a cleared goal comes back active and newer than the clearing`() = runTest {
        profiles.ensureDefault()
        goals.setGoal(startGrams = 82_000, targetGrams = 76_000, milestoneStepGrams = 1_000)
        val before = goals.active()!!

        val undo = goals.clearActive()
        assertThat(goals.active()).isNull()
        // The stamp the clearing wrote. The restore has to beat this, not merely repeat the
        // stamp the goal carried before it was cleared.
        val clearedAt = database.syncDao().goals().single().updatedAtUtcMillis
        assertThat(clearedAt).isGreaterThan(0L)

        undo!!.undo()

        val after = goals.active()!!
        assertThat(after.id).isEqualTo(before.id)
        // Stamped at the restore. Left with the stamp it had, the other device's copy of the
        // clearing is newer and takes the goal away again on the next sync.
        val row = database.syncDao().goals().single()
        assertThat(row.updatedAtUtcMillis).isAtLeast(clearedAt)
    }

    @Test
    fun `deleting something that is not there offers nothing to undo`() = runTest {
        profiles.ensureDefault()
        val missing = com.weighttrack.core.model.Fast(
            id = 404,
            start = Instant.now(),
            targetMinutes = 16 * 60,
        )
        assertThat(fasts.delete(missing)).isNull()
        assertThat(measurements.deleteByIds(emptyList())).isNull()
        assertThat(water.clearDate(LocalDate.of(2026, 1, 1))).isNull()
    }
}
