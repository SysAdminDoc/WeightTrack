package com.weighttrack.data.io

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncSettings
import com.weighttrack.data.db.DeletionEntity
import com.weighttrack.data.db.FastEntity
import com.weighttrack.data.db.FoodEntity
import com.weighttrack.data.db.FoodLogEntryEntity
import com.weighttrack.data.db.GoalEntity
import com.weighttrack.data.db.MacroTargetEntity
import com.weighttrack.data.db.MeasurementEntity
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.RecipeEntity
import com.weighttrack.data.db.RecipeItemEntity
import com.weighttrack.data.db.WaterEntryEntity
import com.weighttrack.data.db.WeightEntryEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.sync.SyncStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A backup has to be the whole phone, or it is not a backup.
 *
 * Version 1 wrote one profile's readings, its measurements and its goals. Everything else, which
 * is water, fasts, macro targets, the profiles themselves with their names and reminder times,
 * and every tombstone, was simply absent, and restoring onto a phone whose profile rows were
 * numbered differently dropped the diary on the floor. This drives a seeded two-profile database
 * out through the file and back into an empty one, and compares the two.
 *
 * Cloud backup is off by design, so this file is the only route to a new phone. Nothing else in
 * the app is this close to losing somebody's history.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    private lateinit var source: WeightTrackDatabase
    private lateinit var restored: WeightTrackDatabase

    private val now = 1_800_000_000_000L

    private fun database(): WeightTrackDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WeightTrackDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        source = database()
        restored = database()
    }

    @After
    fun tearDown() {
        source.close()
        restored.close()
    }

    private val settings = SyncSettings(
        weightUnit = "LB",
        lengthUnit = "IN",
        themeMode = "AMOLED",
        heightMm = 1_803,
        sex = "FEMALE",
        birthYear = 1988,
        activityLevel = "ACTIVE",
        trendWindowDays = 21,
        milestoneStepGrams = 2_500,
        updatedAtUtcMillis = now - 1_000,
    )

    /** Two people, and at least one row in every domain the database holds. */
    private suspend fun seedEverything() {
        val dao = source.syncDao()
        val me = dao.insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                reminderEnabled = true,
                reminderHour = 6,
                reminderMinute = 45,
                reminderDays = "MONDAY,THURSDAY",
                syncId = "p-me",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
        val them = dao.insertProfile(
            ProfileEntity(
                name = "Them",
                position = 1,
                createdAtUtcMillis = now - 90_000,
                syncId = "p-them",
                updatedAtUtcMillis = now - 10_000,
            ),
        )

        dao.insertWeights(listOf(weight(me, "w-me", 80_000), weight(them, "w-them", 61_250)))
        dao.insertMeasurements(
            listOf(
                MeasurementEntity(
                    profileId = me,
                    timestampUtcMillis = now - 40_000,
                    localDate = "2026-08-29",
                    type = "WAIST",
                    valueMm = 900,
                    note = "after a walk",
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "m-me",
                ),
                MeasurementEntity(
                    profileId = them,
                    timestampUtcMillis = now - 40_000,
                    localDate = "2026-08-29",
                    type = "HIPS",
                    valueMm = 980,
                    note = null,
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "m-them",
                ),
            ),
        )
        dao.insertWater(
            listOf(
                WaterEntryEntity(
                    profileId = me,
                    timestampUtcMillis = now - 30_000,
                    localDate = "2026-08-29",
                    millilitres = 330,
                    healthConnectId = null,
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "h-me",
                ),
                WaterEntryEntity(
                    profileId = them,
                    timestampUtcMillis = now - 30_000,
                    localDate = "2026-08-29",
                    millilitres = 500,
                    healthConnectId = null,
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "h-them",
                ),
            ),
        )
        dao.insertFasts(
            listOf(
                FastEntity(
                    profileId = me,
                    startUtcMillis = now - 80_000,
                    endUtcMillis = now - 20_000,
                    targetMinutes = 960,
                    note = "sixteen and eight",
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "f-me",
                ),
                FastEntity(
                    profileId = them,
                    startUtcMillis = now - 70_000,
                    endUtcMillis = null,
                    targetMinutes = 1_080,
                    note = null,
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "f-them",
                ),
            ),
        )
        dao.insertGoals(
            listOf(
                GoalEntity(
                    profileId = me,
                    direction = "LOSE",
                    startGrams = 84_000,
                    targetGrams = 76_000,
                    startDate = "2026-06-01",
                    targetDate = "2026-12-01",
                    milestoneStepGrams = 2_000,
                    active = true,
                    createdAtUtcMillis = now - 90_000,
                    syncId = "g-me",
                    updatedAtUtcMillis = now - 10_000,
                ),
                GoalEntity(
                    profileId = them,
                    direction = "GAIN",
                    startGrams = 58_000,
                    targetGrams = 63_000,
                    startDate = "2026-07-01",
                    targetDate = null,
                    milestoneStepGrams = 1_000,
                    active = true,
                    createdAtUtcMillis = now - 90_000,
                    syncId = "g-them",
                    updatedAtUtcMillis = now - 10_000,
                ),
            ),
        )
        dao.insertMacroTargets(
            listOf(
                MacroTargetEntity(
                    profileId = me,
                    dayOfWeek = null,
                    kcal = 2_100.0,
                    proteinG = 140.0,
                    carbsG = 200.0,
                    fatG = 70.0,
                    basis = "MANUAL",
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "t-me",
                ),
                MacroTargetEntity(
                    profileId = them,
                    dayOfWeek = "SUNDAY",
                    kcal = 2_400.0,
                    proteinG = null,
                    carbsG = null,
                    fatG = null,
                    basis = "ADAPTIVE",
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "t-them",
                ),
            ),
        )
        dao.insertFoods(
            listOf(
                FoodEntity(
                    name = "Oats",
                    brand = "Own",
                    barcode = "5000000000000",
                    kcalPer100g = 370.0,
                    proteinPer100g = 13.0,
                    carbsPer100g = 60.0,
                    fatPer100g = 8.0,
                    fibrePer100g = 10.0,
                    sugarPer100g = 1.0,
                    saltPer100g = 0.01,
                    servingGrams = 40.0,
                    origin = "CUSTOM",
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "food-oats",
                ),
            ),
        )
        dao.insertRecipes(
            listOf(
                RecipeEntity(
                    name = "Porridge",
                    servings = 2,
                    updatedAtUtcMillis = now - 10_000,
                    syncId = "recipe-porridge",
                ),
            ),
        )
        val foodId = dao.foods().single().id
        val recipeId = dao.recipes().single().id
        dao.insertRecipeItems(
            listOf(
                RecipeItemEntity(
                    recipeId = recipeId,
                    foodId = foodId,
                    grams = 80.0,
                    syncId = "item-oats",
                ),
            ),
        )
        dao.insertFoodLog(
            listOf(
                FoodLogEntryEntity(
                    profileId = me,
                    localDate = "2026-08-29",
                    meal = "BREAKFAST",
                    foodId = foodId,
                    name = "Oats",
                    grams = 40.0,
                    kcal = 148.0,
                    proteinG = 5.2,
                    carbsG = 24.0,
                    fatG = 3.2,
                    loggedAtUtcMillis = now - 25_000,
                    syncId = "log-me",
                    updatedAtUtcMillis = now - 10_000,
                ),
                FoodLogEntryEntity(
                    profileId = them,
                    localDate = "2026-08-29",
                    meal = "DINNER",
                    foodId = null,
                    name = "Takeaway",
                    grams = null,
                    kcal = 900.0,
                    proteinG = null,
                    carbsG = null,
                    fatG = null,
                    loggedAtUtcMillis = now - 15_000,
                    syncId = "log-them",
                    updatedAtUtcMillis = now - 10_000,
                ),
            ),
        )
        source.deletionDao().recordAll(
            listOf(DeletionEntity("WEIGHT", "w-gone", now - 5_000, "p-me")),
        )
    }

    private fun weight(profileId: Long, recordId: String, grams: Int) = WeightEntryEntity(
        profileId = profileId,
        timestampUtcMillis = now - 50_000,
        zoneOffsetSeconds = 3_600,
        localDate = "2026-08-29",
        grams = grams,
        bodyFatPercent = 22.5,
        note = "morning",
        tags = "",
        source = "MANUAL",
        clientRecordId = recordId,
        healthConnectId = null,
        updatedAtUtcMillis = now - 10_000,
    )

    /** Out through the file and back in, the same two calls the service makes. */
    private suspend fun roundTrip(): BackupFile {
        val document = SyncStore(source.syncDao(), source.deletionDao())
            .snapshot("backup", now)
            .copy(settings = settings)
        val text = BackupCodec.encode(BackupFile(exportedAtUtcMillis = now, document = document))
        val decoded = checkNotNull(BackupCodec.decode(text))
        SyncStore(restored.syncDao(), restored.deletionDao())
            .apply(checkNotNull(decoded.document), now)
        return decoded
    }

    @Test
    fun `every domain comes back for both people`() = runTest {
        seedEverything()

        roundTrip()

        val dao = restored.syncDao()
        assertThat(dao.profiles().map { it.syncId }).containsExactly("p-me", "p-them")
        assertThat(dao.weights().map { it.clientRecordId }).containsExactly("w-me", "w-them")
        assertThat(dao.measurements().map { it.syncId }).containsExactly("m-me", "m-them")
        assertThat(dao.water().map { it.syncId }).containsExactly("h-me", "h-them")
        assertThat(dao.fasts().map { it.syncId }).containsExactly("f-me", "f-them")
        assertThat(dao.goals().map { it.syncId }).containsExactly("g-me", "g-them")
        assertThat(dao.macroTargets().map { it.syncId }).containsExactly("t-me", "t-them")
        assertThat(dao.foods().map { it.syncId }).containsExactly("food-oats")
        assertThat(dao.recipes().map { it.syncId }).containsExactly("recipe-porridge")
        assertThat(dao.recipeItems().map { it.syncId }).containsExactly("item-oats")
        assertThat(dao.foodLog().map { it.syncId }).containsExactly("log-me", "log-them")
    }

    @Test
    fun `every row lands on the person it belonged to`() = runTest {
        seedEverything()

        roundTrip()

        // Ownership is checked through the travelling names, because the row numbers in the
        // restored database are its own and need not match the ones the source handed out.
        val dao = restored.syncDao()
        val nameOf = dao.profiles().associate { it.id to it.syncId }
        assertThat(dao.weights().associate { it.clientRecordId to nameOf[it.profileId] })
            .containsExactly("w-me", "p-me", "w-them", "p-them")
        assertThat(dao.water().associate { it.syncId to nameOf[it.profileId] })
            .containsExactly("h-me", "p-me", "h-them", "p-them")
        assertThat(dao.fasts().associate { it.syncId to nameOf[it.profileId] })
            .containsExactly("f-me", "p-me", "f-them", "p-them")
        assertThat(dao.goals().associate { it.syncId to nameOf[it.profileId] })
            .containsExactly("g-me", "p-me", "g-them", "p-them")
        assertThat(dao.macroTargets().associate { it.syncId to nameOf[it.profileId] })
            .containsExactly("t-me", "p-me", "t-them", "p-them")
        assertThat(dao.foodLog().associate { it.syncId to nameOf[it.profileId] })
            .containsExactly("log-me", "p-me", "log-them", "p-them")
    }

    @Test
    fun `what is on a profile and what an ingredient points at survive`() = runTest {
        seedEverything()

        roundTrip()

        val dao = restored.syncDao()
        val me = dao.profiles().single { it.syncId == "p-me" }
        assertThat(me.name).isEqualTo("Me")
        assertThat(me.reminderEnabled).isTrue()
        assertThat(me.reminderHour).isEqualTo(6)
        assertThat(me.reminderMinute).isEqualTo(45)
        assertThat(me.reminderDays).isEqualTo("MONDAY,THURSDAY")

        val ingredient = dao.recipeItems().single()
        assertThat(ingredient.recipeId).isEqualTo(dao.recipes().single().id)
        assertThat(ingredient.foodId).isEqualTo(dao.foods().single().id)
        assertThat(dao.foodLog().single { it.syncId == "log-me" }.foodId)
            .isEqualTo(dao.foods().single().id)
    }

    @Test
    fun `the values on the rows come back unchanged`() = runTest {
        seedEverything()

        roundTrip()

        val dao = restored.syncDao()
        val mine = dao.weights().single { it.clientRecordId == "w-me" }
        assertThat(mine.grams).isEqualTo(80_000)
        assertThat(mine.bodyFatPercent).isEqualTo(22.5)
        assertThat(mine.note).isEqualTo("morning")
        assertThat(mine.zoneOffsetSeconds).isEqualTo(3_600)
        assertThat(dao.fasts().single { it.syncId == "f-them" }.endUtcMillis).isNull()
        assertThat(dao.macroTargets().single { it.syncId == "t-them" }.dayOfWeek)
            .isEqualTo("SUNDAY")
        assertThat(dao.foodLog().single { it.syncId == "log-them" }.kcal).isEqualTo(900.0)
    }

    @Test
    fun `the tombstones travel with it`() = runTest {
        seedEverything()

        roundTrip()

        // Without these, a restore hands back everything the person deleted before taking it.
        assertThat(restored.deletionDao().all().map { it.syncId }).contains("w-gone")
    }

    @Test
    fun `the settings and the photo exclusion are in the file`() = runTest {
        seedEverything()

        val decoded = roundTrip()

        assertThat(decoded.document?.settings).isEqualTo(settings)
        assertThat(decoded.formatVersion).isEqualTo(2)
        assertThat(decoded.progressPhotos).contains("archive")
    }

    @Test
    fun `restoring the same file twice changes nothing the second time`() = runTest {
        seedEverything()
        roundTrip()
        val before = restored.syncDao().weights().map { it.id to it.grams }

        roundTrip()

        assertThat(restored.syncDao().weights().map { it.id to it.grams }).isEqualTo(before)
    }

    @Test
    fun `a version one file still restores`() = runTest {
        // What 0.4.0 and earlier wrote: no document, one profile's readings at the top level.
        val text = """
            {
              "app": "WeightTrack",
              "formatVersion": 1,
              "exportedAtUtcMillis": 1750000000000,
              "entries": [
                {
                  "timestampUtcMillis": 1750000000000,
                  "zoneOffsetSeconds": 0,
                  "localDate": "2026-06-15",
                  "grams": 80000,
                  "clientRecordId": "old-1"
                }
              ],
              "measurements": [
                {
                  "timestampUtcMillis": 1750000000000,
                  "localDate": "2026-06-15",
                  "type": "WAIST",
                  "valueMm": 900
                }
              ],
              "goals": [
                {
                  "direction": "LOSE",
                  "startGrams": 84000,
                  "targetGrams": 76000,
                  "startDate": "2026-06-01",
                  "milestoneStepGrams": 2000,
                  "active": true
                }
              ]
            }
        """.trimIndent()

        val decoded = checkNotNull(BackupCodec.decode(text))

        assertThat(decoded.formatVersion).isEqualTo(1)
        // Null is what sends a restore down the old path rather than the document one.
        assertThat(decoded.document).isNull()
        assertThat(decoded.entries.mapNotNull(BackupCodec::backupToEntry)).hasSize(1)
        assertThat(decoded.measurements.mapNotNull(BackupCodec::backupToMeasurement)).hasSize(1)
        assertThat(decoded.goals.mapNotNull(BackupCodec::backupToGoal)).hasSize(1)
    }
}
