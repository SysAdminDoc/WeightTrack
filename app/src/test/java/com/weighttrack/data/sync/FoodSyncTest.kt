package com.weighttrack.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.core.sync.SyncMerge
import com.weighttrack.data.db.FoodEntity
import com.weighttrack.data.db.FoodLogEntryEntity
import com.weighttrack.data.db.ProfileEntity
import com.weighttrack.data.db.RecipeEntity
import com.weighttrack.data.db.RecipeItemEntity
import com.weighttrack.data.db.WeightTrackDatabase
import com.weighttrack.data.repo.DeletionRecorder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The food side crossing between two devices.
 *
 * Left out of the first version of sync, which meant a phone switch lost somebody's whole diary
 * while carrying every weigh-in across. The awkward part is that an ingredient and a logged meal
 * both point at a food, and a row number means nothing on the other device.
 */
@RunWith(RobolectricTestRunner::class)
class FoodSyncTest {

    private lateinit var phone: WeightTrackDatabase
    private lateinit var tablet: WeightTrackDatabase
    private lateinit var phoneStore: SyncStore
    private lateinit var tabletStore: SyncStore

    private val now = 1_800_000_000_000L

    private fun database(): WeightTrackDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        WeightTrackDatabase::class.java,
    ).allowMainThreadQueries().build()

    @Before
    fun setUp() {
        phone = database()
        tablet = database()
        phoneStore = SyncStore(phone, phone.syncDao(), phone.deletionDao())
        tabletStore = SyncStore(tablet, tablet.syncDao(), tablet.deletionDao())
    }

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
    }

    private suspend fun profile(db: WeightTrackDatabase, syncId: String = "p1"): Long =
        db.syncDao().insertProfile(
            ProfileEntity(
                name = "Me",
                position = 0,
                createdAtUtcMillis = now - 100_000,
                syncId = syncId,
                updatedAtUtcMillis = now - 10_000,
            ),
        )

    private fun food(syncId: String, name: String, kcal: Double = 379.0, updatedAt: Long = now) =
        FoodEntity(
            name = name,
            brand = null,
            barcode = null,
            kcalPer100g = kcal,
            proteinPer100g = null,
            carbsPer100g = null,
            fatPer100g = null,
            fibrePer100g = null,
            sugarPer100g = null,
            saltPer100g = null,
            servingGrams = null,
            origin = "CUSTOM",
            updatedAtUtcMillis = updatedAt,
            syncId = syncId,
        )

    private fun logEntry(
        profileId: Long,
        syncId: String,
        foodId: Long?,
        kcal: Double = 379.0,
        updatedAt: Long = now,
    ) = FoodLogEntryEntity(
        profileId = profileId,
        localDate = "2026-08-29",
        meal = "BREAKFAST",
        foodId = foodId,
        name = "Oats",
        grams = 100.0,
        kcal = kcal,
        proteinG = null,
        carbsG = null,
        fatG = null,
        loggedAtUtcMillis = now,
        syncId = syncId,
        updatedAtUtcMillis = updatedAt,
    )

    private suspend fun sync(from: SyncStore, fromId: String, to: SyncStore, toId: String) {
        val documents = listOf(from.snapshot(fromId, now), to.snapshot(toId, now))
        to.apply(SyncMerge.merge(documents, toId, now), now)
    }

    @Test
    fun `a food and the meal made from it both make the trip`() = runTest {
        val me = profile(phone)
        profile(tablet)
        phone.syncDao().insertFoods(listOf(food("f1", "Oats")))
        val foodId = phone.syncDao().foods().single().id
        phone.syncDao().insertFoodLog(listOf(logEntry(me, "l1", foodId)))

        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().foods().map { it.name }).containsExactly("Oats")
        val logged = tablet.syncDao().foodLog().single()
        assertThat(logged.kcal).isWithin(1e-9).of(379.0)
        // Pointed at the tablet's own row for that food, not at the phone's row number.
        assertThat(logged.foodId).isEqualTo(tablet.syncDao().foods().single().id)
    }

    @Test
    fun `a quick add with no food behind it still travels`() = runTest {
        val me = profile(phone)
        profile(tablet)
        phone.syncDao().insertFoodLog(listOf(logEntry(me, "l1", foodId = null, kcal = 250.0)))

        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().foodLog().single().kcal).isWithin(1e-9).of(250.0)
    }

    @Test
    fun `a meal whose food was deleted still counts towards its day`() = runTest {
        val me = profile(phone)
        profile(tablet)
        phone.syncDao().insertFoods(listOf(food("f1", "Oats")))
        val foodId = phone.syncDao().foods().single().id
        phone.syncDao().insertFoodLog(listOf(logEntry(me, "l1", foodId)))
        phone.syncDao().deleteFoods(listOf("f1"))

        sync(phoneStore, "aaa", tabletStore, "bbb")

        // The nutrition rides on the row, so the day still adds up even with nothing to point at.
        val logged = tablet.syncDao().foodLog().single()
        assertThat(logged.kcal).isWithin(1e-9).of(379.0)
        assertThat(logged.foodId).isNull()
    }

    @Test
    fun `a recipe arrives with its ingredients pointing at the right foods`() = runTest {
        profile(phone)
        profile(tablet)
        phone.syncDao().insertFoods(listOf(food("f1", "Oats"), food("f2", "Milk", kcal = 42.0)))
        val foods = phone.syncDao().foods().associate { it.syncId to it.id }
        phone.syncDao().insertRecipes(
            listOf(RecipeEntity(name = "Porridge", servings = 2, updatedAtUtcMillis = now, syncId = "r1")),
        )
        val recipeId = phone.syncDao().recipes().single().id
        phone.syncDao().insertRecipeItems(
            listOf(
                RecipeItemEntity(recipeId = recipeId, foodId = foods.getValue("f1"), grams = 80.0, syncId = "i1"),
                RecipeItemEntity(recipeId = recipeId, foodId = foods.getValue("f2"), grams = 300.0, syncId = "i2"),
            ),
        )

        sync(phoneStore, "aaa", tabletStore, "bbb")

        val there = tablet.syncDao().foods().associate { it.id to it.name }
        val items = tablet.syncDao().recipeItems()
        assertThat(items).hasSize(2)
        // Row numbers mean nothing on the other device, so this is the whole point of the trip.
        assertThat(items.map { there[it.foodId] }).containsExactly("Oats", "Milk")
        assertThat(tablet.syncDao().recipes().single().servings).isEqualTo(2)
    }

    @Test
    fun `syncing the food side again changes nothing`() = runTest {
        val me = profile(phone)
        profile(tablet)
        phone.syncDao().insertFoods(listOf(food("f1", "Oats")))
        phone.syncDao().insertFoodLog(
            listOf(logEntry(me, "l1", phone.syncDao().foods().single().id)),
        )
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val documents = listOf(phoneStore.snapshot("aaa", now), tabletStore.snapshot("bbb", now))
        val second = tabletStore.apply(SyncMerge.merge(documents, "bbb", now), now)

        assertThat(second.touched).isEqualTo(0)
    }

    @Test
    fun `deleting a meal makes it stay deleted`() = runTest {
        val me = profile(phone)
        profile(tablet)
        phone.syncDao().insertFoodLog(listOf(logEntry(me, "l1", foodId = null)))
        sync(phoneStore, "aaa", tabletStore, "bbb")
        assertThat(tablet.syncDao().foodLog()).hasSize(1)

        val recorder = DeletionRecorder(phone.deletionDao(), phone.syncDao())
        phone.syncDao().deleteFoodLog(listOf("l1"))
        recorder.record(SyncKind.FOOD_LOG, "l1", now + 1_000, profileId = me)

        sync(phoneStore, "aaa", tabletStore, "bbb")

        assertThat(tablet.syncDao().foodLog()).isEmpty()
    }

    @Test
    fun `a food corrected on one device is corrected on the other`() = runTest {
        profile(phone)
        profile(tablet)
        phone.syncDao().insertFoods(listOf(food("f1", "Oats", kcal = 379.0, updatedAt = now - 5_000)))
        sync(phoneStore, "aaa", tabletStore, "bbb")

        val onTablet = tablet.syncDao().foods().single()
        tablet.syncDao().updateFoods(
            listOf(onTablet.copy(kcalPer100g = 361.0, updatedAtUtcMillis = now + 1_000)),
        )
        sync(tabletStore, "bbb", phoneStore, "aaa")

        assertThat(phone.syncDao().foods().single().kcalPer100g).isWithin(1e-9).of(361.0)
    }

    @Test
    fun `a favourite stays where it was marked`() = runTest {
        profile(phone)
        profile(tablet)
        phone.syncDao().insertFoods(listOf(food("f1", "Oats")))
        val onPhone = phone.syncDao().foods().single()
        phone.syncDao().updateFoods(listOf(onPhone.copy(favourite = true, lastUsedAtUtcMillis = now)))

        sync(phoneStore, "aaa", tabletStore, "bbb")

        // "Recently used" and "favourite" are facts about one person's phone, not about the food.
        val there = tablet.syncDao().foods().single()
        assertThat(there.favourite).isFalse()
        assertThat(there.lastUsedAtUtcMillis).isEqualTo(0)
    }

    @Test
    fun `deleting a profile takes their diary with it`() = runTest {
        val me = profile(phone, "p1")
        val them = phone.syncDao().insertProfile(
            ProfileEntity(
                name = "Them",
                position = 1,
                createdAtUtcMillis = now - 100_000,
                syncId = "p2",
                updatedAtUtcMillis = now - 10_000,
            ),
        )
        phone.syncDao().insertFoodLog(
            listOf(logEntry(me, "l1", null), logEntry(them, "l2", null)),
        )

        val merged = com.weighttrack.core.sync.SyncDocument(
            deviceId = "bbb",
            writtenAtUtcMillis = now,
            deletions = listOf(
                com.weighttrack.core.sync.SyncDeletion(SyncKind.PROFILE, "p2", now + 1_000),
            ),
        )
        phoneStore.apply(merged, now)

        // Nothing cascades in this schema, so their meals would sit there unreachable forever.
        assertThat(phone.syncDao().foodLog().map { it.syncId }).containsExactly("l1")
    }
}
