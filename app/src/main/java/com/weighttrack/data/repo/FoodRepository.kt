package com.weighttrack.data.repo

import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.core.sync.SyncKind
import com.weighttrack.data.db.FoodDao
import com.weighttrack.data.food.OfflineFoodStore
import com.weighttrack.data.db.FoodEntity
import com.weighttrack.data.db.RecipeEntity
import com.weighttrack.data.db.RecipeItemEntity
import com.weighttrack.data.db.RecipeWithItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A recipe and what it is made of, with the nutrition worked out rather than stored. */
data class Recipe(
    val id: Long,
    val name: String,
    val servings: Int,
    val items: List<RecipeItem>,
) {
    val totalGrams: Double get() = items.sumOf { it.grams }

    /** What the whole thing comes to. */
    val total: Nutrients
        get() = items.fold(Nutrients.NONE) { running, item -> running + item.nutrients }

    /**
     * One portion, which is what somebody actually eats.
     *
     * The total divided by the number of portions. Scaling by grams here would be wrong twice
     * over: the total is already an absolute amount, not a per-hundred-gram figure.
     */
    val perServing: Nutrients
        get() = if (servings <= 0) total else total.forGrams(100.0 / servings)

    val servingGrams: Double? get() = if (servings <= 0) null else totalGrams / servings

    /** A recipe as something that can be logged, priced per hundred grams like any other food. */
    fun asFood(): Food = Food(
        id = -id,
        name = name,
        per100g = if (totalGrams <= 0) Nutrients.NONE else total.forGrams(100.0 * 100.0 / totalGrams),
        servingGrams = servingGrams,
        origin = FoodOrigin.RECIPE,
    )
}

data class RecipeItem(val food: Food, val grams: Double) {
    val nutrients: Nutrients get() = food.forGrams(grams)
}

/**
 * The food database.
 *
 * Deliberately not scoped to a profile. A food is a fact about a product, not about a person,
 * and a household cooking together shares its recipes. What belongs to a person is the eating.
 */
@Singleton
class FoodRepository @Inject constructor(
    private val dao: FoodDao,
    private val offline: OfflineFoodStore,
    private val deletions: DeletionRecorder,
) {
    /**
     * Somebody's own foods first, then the shelf that ships with the app.
     *
     * Their own always come first, however good a match the bundled one looks. Somebody who has
     * corrected a label expects to see their version, and a product they have eaten before is
     * almost always the one they mean.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): Flow<List<Food>> =
        dao.search(query.trim(), limit).map { rows ->
            val mine = rows.map { it.toDomain() }
            val room = limit - mine.size
            if (room <= 0) return@map mine
            val known = mine.mapNotNull { it.barcode }.toSet()
            val names = mine.map { it.label.lowercase() }.toSet()
            mine + offline.search(query, room)
                // The same tin twice, once as theirs and once as the bundled copy, reads as a
                // duplicate rather than as a choice.
                .filterNot { it.barcode in known || it.label.lowercase() in names }
        }

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<Food>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    fun observeFavourites(): Flow<List<Food>> =
        dao.observeFavourites().map { rows -> rows.map { it.toDomain() } }

    fun observeCustom(): Flow<List<Food>> =
        dao.observeByOrigin(FoodOrigin.CUSTOM.name).map { rows -> rows.map { it.toDomain() } }

    suspend fun byId(id: Long): Food? = dao.byId(id)?.toDomain()

    /**
     * A barcode, on the phone only.
     *
     * Their own foods, then the bundled shelf. Nothing here touches the network, so a scan in a
     * shop with no signal still has a good chance of answering.
     */
    suspend fun byBarcode(barcode: String): Food? =
        dao.byBarcode(barcode.trim())?.toDomain() ?: offline.byBarcode(barcode)

    /** Adds a food somebody typed in. */
    suspend fun add(food: Food): Long = dao.insert(food.toEntity())

    suspend fun update(food: Food) {
        val existing = dao.byId(food.id) ?: return
        dao.update(
            food.toEntity().copy(
                id = existing.id,
                favourite = existing.favourite,
                lastUsedAtUtcMillis = existing.lastUsedAtUtcMillis,
                // Carried forward, or the edit arrives on the other device as a second food
                // rather than as a correction to the one already there.
                syncId = existing.syncId,
            ),
        )
    }

    suspend fun delete(food: Food) {
        val existing = dao.byId(food.id) ?: return
        // A food belongs to the household rather than to one person, so its deletion names no
        // profile.
        deletions.asOne {
            dao.delete(existing)
            deletions.record(SyncKind.FOOD, existing.syncId)
        }
    }

    /**
     * Keeps a food looked up online, so scanning the same tin twice does not cost a request
     * against a counted allowance and works with no signal at all.
     */
    suspend fun cache(food: Food): Long = dao.cache(food.toEntity())

    suspend fun setFavourite(id: Long, favourite: Boolean) = dao.setFavourite(id, favourite)

    /** Puts a food at the top of the recents, which is where somebody looks first. */
    suspend fun markUsed(id: Long, atUtcMillis: Long = System.currentTimeMillis()) {
        if (id > 0) dao.markUsed(id, atUtcMillis)
    }

    // ---- recipes ----

    fun observeRecipes(): Flow<List<Recipe>> =
        dao.observeRecipes().map { rows -> rows.map { it.toDomain() } }

    suspend fun recipeById(id: Long): Recipe? = dao.recipeById(id)?.toDomain()

    suspend fun saveRecipe(name: String, servings: Int, items: List<RecipeItem>, id: Long = 0): Long {
        val entity = RecipeEntity(
            id = id,
            name = name.trim().ifBlank { "Recipe" },
            servings = servings.coerceAtLeast(1),
            updatedAtUtcMillis = System.currentTimeMillis(),
        )
        // Editing a recipe deletes the ingredient rows it had, so it is a delete path like any
        // other and lands as one commit. Written as two, the tombstones could go in and the
        // replacement rows not follow: an ingredient carries no time of its own, so a tombstone
        // always outranks it, and the next sync would delete the recipe's contents on every
        // device with nothing said about it.
        return deletions.asOne {
            val recipeId = if (id > 0) {
                // The name it travels under is kept, and so are the ingredients being replaced:
                // the other device still holds those rows and would put them back.
                val existing = dao.recipeById(id)
                deletions.record(
                    SyncKind.RECIPE_ITEM,
                    existing?.items.orEmpty().map { it.syncId },
                )
                dao.updateRecipe(entity.copy(syncId = existing?.recipe?.syncId ?: entity.syncId))
                id
            } else {
                dao.insertRecipe(entity)
            }
            dao.replaceRecipeItems(
                recipeId,
                items.map {
                    RecipeItemEntity(recipeId = recipeId, foodId = it.food.id, grams = it.grams)
                },
            )
            recipeId
        }
    }

    suspend fun deleteRecipe(recipe: Recipe) {
        val existing = dao.recipeById(recipe.id) ?: return
        // The ingredients go with it, and each of them has to be remembered separately: the
        // other device holds them as rows of their own and would hand them back.
        deletions.asOne {
            deletions.record(SyncKind.RECIPE_ITEM, existing.items.map { it.syncId })
            dao.deleteRecipe(existing.recipe)
            deletions.record(SyncKind.RECIPE, existing.recipe.syncId)
        }
    }

    private suspend fun RecipeWithItems.toDomain(): Recipe = Recipe(
        id = recipe.id,
        name = recipe.name,
        servings = recipe.servings,
        // An ingredient whose food has been deleted is dropped rather than counted as nothing,
        // which would quietly make the recipe look lighter than it is.
        items = items.mapNotNull { item ->
            dao.byId(item.foodId)?.let { RecipeItem(it.toDomain(), item.grams) }
        },
    )

    private fun FoodEntity.toDomain(): Food = Food(
        id = id,
        name = name,
        brand = brand,
        barcode = barcode,
        per100g = Nutrients(
            kcal = kcalPer100g,
            proteinG = proteinPer100g,
            carbsG = carbsPer100g,
            fatG = fatPer100g,
            fibreG = fibrePer100g,
            sugarG = sugarPer100g,
            saltG = saltPer100g,
        ),
        servingGrams = servingGrams,
        origin = runCatching { FoodOrigin.valueOf(origin) }.getOrDefault(FoodOrigin.CUSTOM),
    )

    private fun Food.toEntity(): FoodEntity = FoodEntity(
        id = id.coerceAtLeast(0),
        name = name,
        brand = brand,
        barcode = barcode,
        kcalPer100g = per100g.kcal,
        proteinPer100g = per100g.proteinG,
        carbsPer100g = per100g.carbsG,
        fatPer100g = per100g.fatG,
        fibrePer100g = per100g.fibreG,
        sugarPer100g = per100g.sugarG,
        saltPer100g = per100g.saltG,
        servingGrams = servingGrams,
        origin = origin.name,
        updatedAtUtcMillis = System.currentTimeMillis(),
    )

    companion object {
        const val SEARCH_LIMIT = 50
        const val RECENT_LIMIT = 20
    }
}
