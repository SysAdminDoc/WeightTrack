package com.weighttrack.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.FoodOrigin
import com.weighttrack.core.nutrition.OpenFoodFactsClient
import com.weighttrack.core.nutrition.UsdaFoodDataClient
import com.weighttrack.data.repo.Recipe
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodScreen(
    state: FoodUiState,
    onQueryChange: (String) -> Unit,
    onSearchOnline: () -> Unit,
    onKeep: (Food) -> Unit,
    onFavourite: (Food, Boolean) -> Unit,
    onDelete: (Food) -> Unit,
    onAddCustom: (String, String?, Double, Double?, Double?, Double?, Double?) -> Unit,
    onDeleteRecipe: (Recipe) -> Unit,
    onScan: () -> Unit,
    onSetUsdaKey: (String) -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onDismissMessage()
    }
    var adding by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Foods") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search your foods") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan) { Text("Scan a barcode") }
                        OutlinedButton(onClick = { adding = true }) { Text("Add a food") }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onSearchOnline,
                        enabled = state.hasQuery && !state.searchingOnline,
                    ) { Text("Look it up online") }
                    if (state.searchingOnline) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator()
                    }
                }
            }

            if (state.online.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionHeading("Found online")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // The licence asks for this, and the data is somebody else's work
                            // given away on the condition that it is credited.
                            text = OpenFoodFactsClient.ATTRIBUTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = UsdaFoodDataClient.ATTRIBUTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.online, key = { it.name + it.barcode.orEmpty() }) { food ->
                    FoodRow(
                        food = food,
                        trailing = {
                            TextButton(onClick = { onKeep(food) }) { Text("Keep") }
                        },
                    )
                }
            }

            // One list, already ordered favourites first, then what was eaten most recently,
            // then the rest. An empty search matches everything, so a food added a moment ago
            // is in it: the old fallback showed recents or favourites and a new food is
            // neither, so it went in and vanished.
            val shown = state.local
            if (shown.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionHeading(if (state.hasQuery) "Matching foods" else "Your foods")
                        if (needsOpenFoodFactsCredit(shown)) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                // The shelf that ships with the app is somebody else's work,
                                // given away on the condition that it is credited. It gets
                                // credited wherever it is shown, not only when it came off the
                                // network.
                                text = OpenFoodFactsClient.ATTRIBUTION,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(shown, key = { foodKey(it) }) { food ->
                    FoodRow(
                        food = food,
                        trailing = {
                            if (food.id == 0L) {
                                // Off the bundled shelf. There is nothing yet to favourite or
                                // delete, so the only thing worth offering is keeping it.
                                TextButton(onClick = { onKeep(food) }) { Text("Keep") }
                            } else {
                                IconButton(
                                    onClick = { onFavourite(food, !isFavourite(food, state)) },
                                ) {
                                    Icon(
                                        imageVector = if (isFavourite(food, state)) {
                                            Icons.Filled.Star
                                        } else {
                                            Icons.Outlined.StarBorder
                                        },
                                        contentDescription = "Favourite",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (food.origin == FoodOrigin.CUSTOM) {
                                    TextButton(onClick = { onDelete(food) }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        },
                    )
                }
            }

            if (state.recipes.isNotEmpty()) {
                item {
                    SectionCard { SectionHeading("Recipes") }
                }
                items(state.recipes, key = { "recipe-${it.id}" }) { recipe ->
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${recipe.items.size} ingredients, " +
                                        "${recipe.perServing.kcal.roundToInt()} kcal a portion",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onDeleteRecipe(recipe) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    SectionHeading("Ingredients from USDA")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // A crowdsourced barcode database knows about a tin of beans and knows
                        // nothing about a potato.
                        text = "Open Food Facts covers packaged food. USDA FoodData Central covers plain ingredients, and needs a free key of your own. This app will not ship one: a key inside an open source app is a shared quota in a public repository.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { editingKey = true }) { Text("Enter a USDA key") }
                }
            }

            if (shown.isEmpty() && state.online.isEmpty() && state.recipes.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = "Nothing here yet. Add a food you eat often, or look one up online and keep it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AddFoodDialog(
            onCancel = { adding = false },
            onSave = { name, brand, kcal, protein, carbs, fat, serving ->
                onAddCustom(name, brand, kcal, protein, carbs, fat, serving)
                adding = false
            },
        )
    }

    if (editingKey) {
        UsdaKeyDialog(
            onCancel = { editingKey = false },
            onSave = {
                onSetUsdaKey(it)
                editingKey = false
            },
        )
    }
}

private fun isFavourite(food: Food, state: FoodUiState): Boolean =
    state.favourites.any { it.id == food.id }

@Composable
private fun FoodRow(food: Food, trailing: @Composable () -> Unit) {
    SectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(food.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = buildString {
                        append("${food.per100g.kcal.roundToInt()} kcal per 100 g")
                        food.per100g.proteinG?.let { append(" · ${it.roundToInt()} g protein") }
                        food.servingGrams?.let { append(" · serving ${it.roundToInt()} g") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
        }
    }
}

/**
 * A food somebody types in.
 *
 * Everything but the name and the calories is optional, because a label often gives only those
 * and refusing the food would be worse than storing what is on it.
 */
@Composable
private fun AddFoodDialog(
    onCancel: () -> Unit,
    onSave: (String, String?, Double, Double?, Double?, Double?, Double?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var serving by remember { mutableStateOf("") }

    val kcalValue = kcal.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && kcalValue != null && kcalValue >= 0

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add a food") },
        text = {
            Column {
                Text(
                    text = "Everything is per 100 grams, which is how labels are written and how this app stores it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NumberlessField(name, { name = it }, "Name")
                NumberlessField(brand, { brand = it }, "Brand (optional)")
                NumberField(kcal, { kcal = it }, "Calories per 100 g")
                NumberField(protein, { protein = it }, "Protein g (optional)")
                NumberField(carbs, { carbs = it }, "Carbohydrate g (optional)")
                NumberField(fat, { fat = it }, "Fat g (optional)")
                NumberField(serving, { serving = it }, "One serving in g (optional)")
                if (!valid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "A name and the calories per 100 grams are all that is needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        name,
                        brand.takeIf { it.isNotBlank() },
                        kcalValue ?: 0.0,
                        protein.replace(',', '.').toDoubleOrNull(),
                        carbs.replace(',', '.').toDoubleOrNull(),
                        fat.replace(',', '.').toDoubleOrNull(),
                        serving.replace(',', '.').toDoubleOrNull(),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun UsdaKeyDialog(onCancel: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("USDA FoodData Central key") },
        text = {
            Column {
                Text(
                    text = "Get a free key at ${UsdaFoodDataClient.KEY_SIGNUP_URL} and paste it here. It stays on this phone. Leave it empty to clear it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NumberlessField(key, { key = it }, "Key")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun NumberlessField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
    )
}

/**
 * A field that can only hold a number.
 *
 * Filtered rather than merely validated. A keyboard is free to send whatever it likes, and some
 * do: a stray character in a calorie box leaves the save button greyed out with nothing on
 * screen explaining why, and the person retyping the same digits gets the same result.
 */
@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(keepNumeric(it)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
    )
}

/** Digits and at most one decimal separator, whatever the keyboard tried to send. */
internal fun keepNumeric(text: String): String {
    var seenSeparator = false
    return buildString {
        text.forEach { character ->
            when {
                character.isDigit() -> append(character)
                (character == '.' || character == ',') && !seenSeparator && isNotEmpty() -> {
                    seenSeparator = true
                    append('.')
                }
            }
        }
    }
}

/**
 * What tells one row in a food list from another.
 *
 * Not the identifier on its own. Everything off the bundled shelf carries a zero, and a lazy list
 * given the same key twice does not merely look wrong, it throws.
 */
internal fun foodKey(food: Food): String =
    if (food.id != 0L) "food-${food.id}" else "shelf-${food.barcode.orEmpty()}-${food.name}"

/**
 * Whether a list of foods carries Open Food Facts data and so has to credit it.
 *
 * The licence follows the data, not the row it happens to be sitting in. Keeping a product off
 * the bundled shelf copies it into somebody's own foods, where it is still Open Food Facts data
 * and still has to say so, and the scanner does that copying without anybody deciding to. Asking
 * whether a food had been kept yet credited the licence right up until the moment it stopped
 * being true.
 */
internal fun needsOpenFoodFactsCredit(foods: List<Food>): Boolean =
    foods.any { it.origin == FoodOrigin.OPEN_FOOD_FACTS }
