package com.weighttrack.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.Nutrients
import com.weighttrack.core.nutrition.OpenFoodFactsClient
import com.weighttrack.core.nutrition.UsdaFoodDataClient
import com.weighttrack.data.food.FoodClients
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.FoodRepository
import com.weighttrack.data.repo.Recipe
import com.weighttrack.data.repo.RecipeItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FoodUiState(
    val query: String = "",
    /** Foods already on the phone, which is where a search should start and usually end. */
    val local: List<Food> = emptyList(),
    val recent: List<Food> = emptyList(),
    val favourites: List<Food> = emptyList(),
    val custom: List<Food> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    /** What came back from a database on the internet, kept apart from what is already here. */
    val online: List<Food> = emptyList(),
    val searchingOnline: Boolean = false,
    val message: String? = null,
) {
    val hasQuery: Boolean get() = query.trim().length >= MIN_QUERY

    companion object {
        const val MIN_QUERY = 2
    }
}

/**
 * The food database.
 *
 * Local first, always. The phone already holds everything anybody has eaten before, and a search
 * that goes to the internet before looking at that is slower, works on a train, and spends an
 * allowance the service counts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FoodViewModel @Inject constructor(
    private val foodRepository: FoodRepository,
    private val clients: FoodClients,
    private val settingsRepository: SettingsRepository,
    /** Whichever reader this build has: ML Kit in the Play flavour, ZXing in the F-Droid one. */
    val barcodeReader: com.weighttrack.barcode.BarcodeReader,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val online = MutableStateFlow<List<Food>>(emptyList())
    private val searchingOnline = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private var onlineJob: Job? = null

    val state: StateFlow<FoodUiState> = combine(
        query.flatMapLatest { foodRepository.search(it) },
        foodRepository.observeRecent(),
        foodRepository.observeFavourites(),
        foodRepository.observeCustom(),
        combine(foodRepository.observeRecipes(), online, searchingOnline, message, query) {
            recipes, online, searching, message, query ->
            Extras(recipes, online, searching, message, query)
        },
    ) { local, recent, favourites, custom, extras ->
        FoodUiState(
            query = extras.query,
            local = local,
            recent = recent,
            favourites = favourites,
            custom = custom,
            recipes = extras.recipes,
            online = extras.online,
            searchingOnline = extras.searching,
            message = extras.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodUiState())

    private data class Extras(
        val recipes: List<Recipe>,
        val online: List<Food>,
        val searching: Boolean,
        val message: String?,
        val query: String,
    )

    fun setQuery(text: String) {
        query.value = text
        online.value = emptyList()
    }

    /** Asks the food databases, which is a deliberate act rather than something typing does. */
    fun searchOnline() {
        val text = query.value.trim()
        if (text.length < FoodUiState.MIN_QUERY) return
        onlineJob?.cancel()
        onlineJob = viewModelScope.launch {
            searchingOnline.value = true
            try {
                val found = mutableListOf<Food>()
                when (val result = clients.openFoodFacts().search(text)) {
                    is OpenFoodFactsClient.Result.Found -> found += result.value
                    is OpenFoodFactsClient.Result.RateLimited ->
                        message.value = "Open Food Facts asks for a moment. Try again in " +
                            "${(result.retryInMillis / 1000) + 1} seconds."
                    is OpenFoodFactsClient.Result.Unreachable ->
                        message.value = "Could not reach Open Food Facts."
                    is OpenFoodFactsClient.Result.NotFound -> Unit
                }
                when (val result = clients.usda().search(text)) {
                    is UsdaFoodDataClient.Result.Found -> found += result.value
                    // Not having a key is a thing left undone, not a failure worth shouting about.
                    is UsdaFoodDataClient.Result.NoKey -> Unit
                    else -> Unit
                }
                online.value = found
                if (found.isEmpty() && message.value == null) {
                    message.value = "Nothing found for \"$text\"."
                }
            } finally {
                searchingOnline.value = false
            }
        }
    }

    /** Looks a barcode up, on the phone first. */
    fun lookUpBarcode(barcode: String, onResult: (Food?) -> Unit = {}) {
        viewModelScope.launch {
            foodRepository.byBarcode(barcode)?.let { known ->
                // Already here, so no request and no signal needed.
                if (known.id > 0) {
                    message.value = "${known.label} is already in your foods."
                    onResult(known)
                    return@launch
                }
                // Off the bundled shelf. Kept, so it behaves like any other food from now on and
                // so a correction to it sticks.
                val id = foodRepository.cache(known)
                message.value = "${known.label} added to your foods."
                onResult(foodRepository.byId(id))
                return@launch
            }
            when (val result = clients.openFoodFacts().byBarcode(barcode)) {
                is OpenFoodFactsClient.Result.Found -> {
                    val id = foodRepository.cache(result.value)
                    message.value = "${result.value.label} added to your foods."
                    onResult(foodRepository.byId(id))
                }
                is OpenFoodFactsClient.Result.RateLimited -> {
                    message.value = "Too many lookups just now. Try again in a moment."
                    onResult(null)
                }
                else -> {
                    message.value = "No food found for that barcode."
                    onResult(null)
                }
            }
        }
    }

    /** Keeps a food found online, so it is there next time with no signal and no request. */
    fun keep(food: Food) {
        viewModelScope.launch {
            foodRepository.cache(food)
            online.value = online.value.filterNot { it === food }
            message.value = "${food.name} saved to your foods."
        }
    }

    fun addCustom(
        name: String,
        brand: String?,
        kcal: Double,
        proteinG: Double?,
        carbsG: Double?,
        fatG: Double?,
        servingGrams: Double?,
    ) {
        viewModelScope.launch {
            foodRepository.add(
                Food(
                    name = name.trim(),
                    brand = brand?.trim()?.takeIf { it.isNotEmpty() },
                    per100g = Nutrients(
                        kcal = kcal,
                        proteinG = proteinG,
                        carbsG = carbsG,
                        fatG = fatG,
                    ),
                    servingGrams = servingGrams?.takeIf { it > 0 },
                    origin = FoodOrigin.CUSTOM,
                ),
            )
            message.value = "${name.trim()} added."
        }
    }

    fun setFavourite(food: Food, favourite: Boolean) {
        viewModelScope.launch { foodRepository.setFavourite(food.id, favourite) }
    }

    fun delete(food: Food) {
        viewModelScope.launch { foodRepository.delete(food) }
    }

    fun saveRecipe(name: String, servings: Int, items: List<RecipeItem>, id: Long = 0) {
        viewModelScope.launch {
            foodRepository.saveRecipe(name, servings, items, id)
            message.value = "${name.trim()} saved."
        }
    }

    fun deleteRecipe(recipe: Recipe) {
        viewModelScope.launch { foodRepository.deleteRecipe(recipe) }
    }

    fun dismissMessage() {
        message.value = null
    }

    /** Where somebody puts their own key, since this app will not ship a shared one. */
    fun setUsdaKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setUsdaApiKey(key.trim().takeIf { it.isNotEmpty() })
            message.value = if (key.isBlank()) {
                "USDA key cleared."
            } else {
                "USDA key saved. Ingredient searches will include it."
            }
        }
    }

    companion object {
        /** Long enough that a search does not fire on the first letter typed. */
        const val DEBOUNCE_MILLIS = 350L
    }
}
