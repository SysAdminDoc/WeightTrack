package com.weighttrack.ui.diary

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import com.weighttrack.core.nutrition.Meal
import com.weighttrack.data.repo.DayLog
import com.weighttrack.data.repo.FoodLogEntry
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.ui.components.SectionHeading
import com.weighttrack.ui.components.SegmentButton
import com.weighttrack.ui.food.keepNumeric
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.ui.theme.HeroNumberStyle
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    state: DiaryUiState,
    suggestedMeal: Meal,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onQueryChange: (String) -> Unit,
    onLog: (Food, Double, Meal) -> Unit,
    onQuickAdd: (Double, Meal, String) -> Unit,
    onCopyYesterday: (Meal?) -> Unit,
    onSetTarget: (Double, Double?, Double?, Double?, MacroBasis, java.time.DayOfWeek?) -> Unit,
    onDelete: (FoodLogEntry) -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onDismissMessage()
    }
    var adding by remember { mutableStateOf<Meal?>(null) }
    var quickAdding by remember { mutableStateOf<Meal?>(null) }
    var editingTarget by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Food diary") },
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onPreviousDay) { Text("Previous") }
                        Text(
                            text = DateFormatters.relativeDay(state.date, today),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = onNextDay, enabled = !state.isToday) { Text("Next") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${state.day.total.kcal.roundToInt()}",
                            style = HeroNumberStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "kcal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.remaining?.let { left ->
                        Text(
                            text = if (left.isOver) {
                                "${-left.kcalRounded} over"
                            } else {
                                "${left.kcalRounded} left of ${state.target!!.kcal.roundToInt()}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (left.isOver) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    MacroRow(state.day, state.target)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCopyYesterday(null) }) {
                            Text("Copy the day before")
                        }
                        OutlinedButton(onClick = { editingTarget = true }) {
                            Text(if (state.target == null) "Set a target" else "Target")
                        }
                    }
                }
            }

            Meal.entries.forEach { meal ->
                item(key = "meal-${meal.name}") {
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionHeading(meal.label)
                            Text(
                                text = "${state.day.totalFor(meal).kcal.roundToInt()} kcal",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.day.forMeal(meal).forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = entry.grams
                                            ?.let { "${it.roundToInt()} g · ${entry.nutrients.kcal.roundToInt()} kcal" }
                                            ?: "${entry.nutrients.kcal.roundToInt()} kcal",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onDelete(entry) }) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { adding = meal }) { Text("Add") }
                            OutlinedButton(onClick = { quickAdding = meal }) { Text("Quick add") }
                            if (state.day.forMeal(meal).isEmpty()) {
                                TextButton(onClick = { onCopyYesterday(meal) }) {
                                    Text("Same as yesterday")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    adding?.let { meal ->
        AddFromFoodsDialog(
            meal = meal,
            query = state.query,
            suggestions = state.suggestions,
            results = state.searchResults,
            onQueryChange = onQueryChange,
            onCancel = { adding = null },
            onLog = { food, grams ->
                onLog(food, grams, meal)
                adding = null
            },
        )
    }

    if (editingTarget) {
        TargetDialog(
            current = state.target,
            day = state.date.dayOfWeek,
            onCancel = { editingTarget = false },
            onSave = { kcal, protein, carbs, fat, basis, day ->
                onSetTarget(kcal, protein, carbs, fat, basis, day)
                editingTarget = false
            },
        )
    }

    quickAdding?.let { meal ->
        QuickAddDialog(
            meal = meal,
            onCancel = { quickAdding = null },
            onAdd = { kcal, name ->
                onQuickAdd(kcal, meal, name)
                quickAdding = null
            },
        )
    }
}

@Composable
private fun MacroRow(day: DayLog, target: MacroTarget?) {
    val total = day.total
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        // Only what is actually known. A day with one quick-add in it has no honest protein
        // figure, and printing zero would be a claim nobody made.
        LabelledValue("Protein", macroText(total.proteinG, target?.proteinG))
        LabelledValue("Carbs", macroText(total.carbsG, target?.carbsG))
        LabelledValue("Fat", macroText(total.fatG, target?.fatG))
    }
}

private fun macroText(eaten: Double?, target: Double?): String {
    val amount = eaten?.let { "${it.roundToInt()} g" } ?: "--"
    return target?.let { "$amount / ${it.roundToInt()} g" } ?: amount
}

/**
 * Picking something to eat.
 *
 * Opens on what this person actually eats rather than an empty search box, because after the
 * first week that list is the answer almost every time.
 */
@Composable
private fun AddFromFoodsDialog(
    meal: Meal,
    query: String,
    suggestions: List<Food>,
    results: List<Food>,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    onLog: (Food, Double) -> Unit,
) {
    var chosen by remember { mutableStateOf<Food?>(null) }
    var grams by remember { mutableStateOf("") }

    val food = chosen
    if (food == null) {
        val shown = if (results.isNotEmpty()) results else suggestions
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Add to ${meal.label.lowercase()}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search") },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (shown.isEmpty()) {
                        Text(
                            text = "Nothing to add yet. Add a food or scan a barcode on the Foods screen first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    shown.take(8).forEach { candidate ->
                        TextButton(
                            onClick = {
                                chosen = candidate
                                grams = candidate.servingGrams?.roundToInt()?.toString() ?: "100"
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(candidate.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    } else {
        val amount = grams.toDoubleOrNull()
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(food.label) },
            text = {
                Column {
                    OutlinedTextField(
                        value = grams,
                        onValueChange = { grams = keepNumeric(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Grams") },
                    )
                    Spacer(Modifier.height(6.dp))
                    food.servingGrams?.let {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SegmentButton(
                                label = "1 serving",
                                selected = amount == it,
                                onClick = { grams = it.roundToInt().toString() },
                            )
                            SegmentButton(
                                label = "2 servings",
                                selected = amount == it * 2,
                                onClick = { grams = (it * 2).roundToInt().toString() },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        text = amount
                            ?.let { "${food.kcalForGrams(it)} kcal" }
                            ?: "Enter how much of it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = amount != null && amount > 0,
                    onClick = { onLog(food, amount ?: 0.0) },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Back") } },
        )
    }
}

@Composable
private fun QuickAddDialog(meal: Meal, onCancel: () -> Unit, onAdd: (Double, String) -> Unit) {
    var kcal by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val amount = kcal.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Quick add to ${meal.label.lowercase()}") },
        text = {
            Column {
                Text(
                    // The escape hatch that stops a food log becoming a chore.
                    text = "A meal out has no barcode. Put in roughly what it was and get on with your day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = kcal,
                    onValueChange = { kcal = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Calories") },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("What was it (optional)") },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount != null && amount > 0,
                onClick = { onAdd(amount ?: 0.0, name) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * What a day should come to.
 *
 * Grams or shares, whichever the person thinks in. Lifting and medical advice come in grams,
 * most diets are described as percentages, and the app converts rather than insisting.
 */
@Composable
private fun TargetDialog(
    current: MacroTarget?,
    day: java.time.DayOfWeek,
    onCancel: () -> Unit,
    onSave: (Double, Double?, Double?, Double?, MacroBasis, java.time.DayOfWeek?) -> Unit,
) {
    var basis by remember { mutableStateOf(current?.basis ?: MacroBasis.GRAMS) }
    var kcal by remember { mutableStateOf(current?.kcal?.roundToInt()?.toString() ?: "") }
    var protein by remember { mutableStateOf(fieldFor(current?.proteinG, current?.proteinPercent, basis)) }
    var carbs by remember { mutableStateOf(fieldFor(current?.carbsG, current?.carbsPercent, basis)) }
    var fat by remember { mutableStateOf(fieldFor(current?.fatG, current?.fatPercent, basis)) }
    var justThisDay by remember { mutableStateOf(false) }

    val kcalValue = kcal.toDoubleOrNull()
    val valid = kcalValue != null && kcalValue > 0

    fun grams(text: String, kcalPerGram: Double): Double? {
        val value = text.toDoubleOrNull() ?: return null
        return if (basis == MacroBasis.GRAMS) {
            value
        } else {
            MacroTarget.gramsFromPercent(kcalValue ?: 0.0, value, kcalPerGram)
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Daily target") },
        text = {
            Column {
                OutlinedTextField(
                    value = kcal,
                    onValueChange = { kcal = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Calories") },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroBasis.entries.forEach { option ->
                        SegmentButton(
                            label = if (option == MacroBasis.GRAMS) "Grams" else "Percent",
                            selected = basis == option,
                            onClick = { basis = option },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val suffix = if (basis == MacroBasis.GRAMS) "g" else "%"
                OutlinedTextField(
                    value = protein,
                    onValueChange = { protein = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Protein $suffix (optional)") },
                )
                OutlinedTextField(
                    value = carbs,
                    onValueChange = { carbs = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Carbohydrate $suffix (optional)") },
                )
                OutlinedTextField(
                    value = fat,
                    onValueChange = { fat = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Fat $suffix (optional)") },
                )
                Spacer(Modifier.height(6.dp))
                SegmentButton(
                    label = "Only on a " + day.name.lowercase().replaceFirstChar(Char::uppercase),
                    selected = justThisDay,
                    onClick = { justThisDay = !justThisDay },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Eating the same on a rest day as on a long run is what this is for.
                    text = "Leave that off and this is the target for every day that has not got one of its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        kcalValue ?: 0.0,
                        grams(protein, MacroTarget.KCAL_PER_GRAM_PROTEIN),
                        grams(carbs, MacroTarget.KCAL_PER_GRAM_CARBS),
                        grams(fat, MacroTarget.KCAL_PER_GRAM_FAT),
                        basis,
                        day.takeIf { justThisDay },
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private fun fieldFor(grams: Double?, percent: Double?, basis: MacroBasis): String {
    val value = if (basis == MacroBasis.GRAMS) grams else percent
    return value?.roundToInt()?.toString().orEmpty()
}
