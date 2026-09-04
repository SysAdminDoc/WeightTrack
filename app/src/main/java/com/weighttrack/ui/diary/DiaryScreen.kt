package com.weighttrack.ui.diary

import com.weighttrack.core.format.LocaleNumbers
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weighttrack.R
import com.weighttrack.ui.format.Labels
import com.weighttrack.core.nutrition.Food
import com.weighttrack.core.nutrition.MacroBasis
import com.weighttrack.core.nutrition.MacroTarget
import com.weighttrack.core.nutrition.Meal
import com.weighttrack.data.repo.DayLog
import com.weighttrack.data.repo.FoodLogEntry
import com.weighttrack.ui.components.LabelledValue
import com.weighttrack.ui.components.SectionCard
import com.weighttrack.core.nutrition.OpenFoodFactsClient
import com.weighttrack.ui.food.needsOpenFoodFactsCredit
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
    onUseRecommendation: () -> Unit,
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
                title = { Text(stringResource(R.string.diary_food_diary)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                        TextButton(onClick = onPreviousDay) { Text(stringResource(R.string.common_previous)) }
                        Text(
                            text = DateFormatters.relativeDay(state.date, today),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = onNextDay, enabled = !state.isToday) { Text(stringResource(R.string.common_next)) }
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
                            text = stringResource(R.string.diary_kcal),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.remaining?.let { left ->
                        Text(
                            text = if (left.isOver) {
                                "${-left.kcalRounded} over"
                            } else {
                                stringResource(R.string.diaryscreen_left, left.kcalRounded, state.target!!.kcal.roundToInt())
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
                            Text(stringResource(R.string.diary_copy_the_day_before))
                        }
                        OutlinedButton(onClick = { editingTarget = true }) {
                            Text(
                                stringResource(
                                    if (state.target == null) {
                                        R.string.diaryscreen_set_target
                                    } else {
                                        R.string.diaryscreen_target
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            state.expenditure?.let { estimate ->
                item {
                    SectionCard {
                        SectionHeading(stringResource(R.string.diary_what_you_burn))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.diary_about_kcal_a_day, estimate.rounded),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            // No formula and no activity multiplier. Their own body measured it.
                            text = if (state.expenditureConfident) {
                                stringResource(R.string.diaryscreen_worked_out_from_days_food_what, estimate.loggedDays)
                            } else {
                                // Hedged rather than stated. The arithmetic ran, but a fortnight
                                // with gaps in the food or a large swing in weight is not yet a
                                // fact about anybody's metabolism.
                                stringResource(R.string.diaryscreen_still_settling_worked_out_from_days, estimate.loggedDays, estimate.days)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (estimate.movementChanged) {
                            // Said out loud. A number that moves two hundred calories with
                            // nothing on screen about why reads as the app being unreliable.
                            Text(
                                text = stringResource(R.string.diary_movement_changed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.recommendation?.takeIf { state.expenditureConfident }?.let { recommended ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (recommended.cappedAtMinimum) {
                                    stringResource(R.string.diaryscreen_your_goal_would_need_less_than, recommended.rounded)
                                } else {
                                    stringResource(R.string.diaryscreen_eat_about_kcal_day_keep_going, recommended.rounded)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!recommended.cappedAtMinimum) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = onUseRecommendation) {
                                    Text(stringResource(R.string.diary_make_that_my_target))
                                }
                            }
                        }
                        // Only for somebody keeping an injection log. Losing weight this fast
                        // takes muscle with it unless protein stays up, and the day's eating is
                        // the one place that number is any use.
                        state.proteinGrams?.let { grams ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.medication_protein_target,
                                    grams.first,
                                    grams.last,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.medication_protein_explained),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                            SectionHeading(stringResource(Labels.of(meal)))
                            Text(
                                text = stringResource(R.string.diary_kcal_2, state.day.totalFor(meal).kcal.roundToInt()),
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
                                            ?.let { stringResource(R.string.diaryscreen_g_kcal, it.roundToInt(), entry.nutrients.kcal.roundToInt()) }
                                            ?: stringResource(
                                                R.string.diaryscreen_kcal_only,
                                                entry.nutrients.kcal.roundToInt(),
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onDelete(entry) }) {
                                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { adding = meal }) { Text(stringResource(R.string.common_add)) }
                            OutlinedButton(onClick = { quickAdding = meal }) { Text(stringResource(R.string.diary_quick_add)) }
                            if (state.day.forMeal(meal).isEmpty()) {
                                TextButton(onClick = { onCopyYesterday(meal) }) {
                                    Text(stringResource(R.string.diary_same_as_yesterday))
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
            dayHasItsOwn = state.targetIsForThisDay,
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
        LabelledValue(stringResource(R.string.diaryscreen_protein), macroText(total.proteinG, target?.proteinG))
        LabelledValue(stringResource(R.string.diaryscreen_carbs), macroText(total.carbsG, target?.carbsG))
        LabelledValue(stringResource(R.string.diaryscreen_fat), macroText(total.fatG, target?.fatG))
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
            title = { Text(stringResource(R.string.diary_add_to, stringResource(Labels.of(meal)).lowercase())) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.diary_search)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (shown.isEmpty()) {
                        Text(
                            text = stringResource(R.string.diary_nothing_to_add_yet_add_a),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (needsOpenFoodFactsCredit(shown)) {
                        Text(
                            // Credited here too. The shelf shows up in this list as readily as
                            // on the Foods screen.
                            text = OpenFoodFactsClient.ATTRIBUTION,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
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
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
        )
    } else {
        val amount = LocaleNumbers.decimal(grams)
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
                        label = { Text(stringResource(R.string.diary_grams)) },
                    )
                    Spacer(Modifier.height(6.dp))
                    food.servingGrams?.let {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SegmentButton(
                                label = stringResource(R.string.diary_serving),
                                selected = amount == it,
                                onClick = { grams = it.roundToInt().toString() },
                            )
                            SegmentButton(
                                label = stringResource(R.string.diary_servings),
                                selected = amount == it * 2,
                                onClick = { grams = (it * 2).roundToInt().toString() },
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        text = amount
                            ?.let { "${food.kcalForGrams(it)} kcal" }
                            ?: stringResource(R.string.diaryscreen_enter_how_much),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = amount != null && amount > 0,
                    onClick = { onLog(food, amount ?: 0.0) },
                ) { Text(stringResource(R.string.common_add)) }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.common_back)) } },
        )
    }
}

@Composable
private fun QuickAddDialog(meal: Meal, onCancel: () -> Unit, onAdd: (Double, String) -> Unit) {
    var kcal by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val amount = LocaleNumbers.decimal(kcal)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.diary_quick_add_to, stringResource(Labels.of(meal)).lowercase())) },
        text = {
            Column {
                Text(
                    // The escape hatch that stops a food log becoming a chore.
                    text = stringResource(R.string.diary_a_meal_out_has_no_barcode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = kcal,
                    onValueChange = { kcal = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.diary_calories)) },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.diary_what_was_it_optional)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount != null && amount > 0,
                onClick = { onAdd(amount ?: 0.0, name) },
            ) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
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
    /**
     * Whether the target on screen is this weekday's own rather than the everyday one.
     *
     * Seeds the switch below. Started at false, editing a Saturday target wrote the answer into
     * the everyday row instead: the other six days lost theirs, Saturday looked exactly as it
     * had, and the message said the target was set.
     */
    dayHasItsOwn: Boolean,
    onCancel: () -> Unit,
    onSave: (Double, Double?, Double?, Double?, MacroBasis, java.time.DayOfWeek?) -> Unit,
) {
    var basis by remember { mutableStateOf(current?.basis ?: MacroBasis.GRAMS) }
    var kcal by remember { mutableStateOf(current?.kcal?.roundToInt()?.toString() ?: "") }
    var protein by remember { mutableStateOf(fieldFor(current?.proteinG, current?.proteinPercent, basis)) }
    var carbs by remember { mutableStateOf(fieldFor(current?.carbsG, current?.carbsPercent, basis)) }
    var fat by remember { mutableStateOf(fieldFor(current?.fatG, current?.fatPercent, basis)) }
    var justThisDay by remember { mutableStateOf(dayHasItsOwn) }

    val kcalValue = LocaleNumbers.decimal(kcal)
    val valid = kcalValue != null && kcalValue > 0

    fun grams(text: String, kcalPerGram: Double): Double? {
        val value = LocaleNumbers.decimal(text) ?: return null
        return if (basis == MacroBasis.GRAMS) {
            value
        } else {
            MacroTarget.gramsFromPercent(kcalValue ?: 0.0, value, kcalPerGram)
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.diary_daily_target)) },
        text = {
            Column {
                OutlinedTextField(
                    value = kcal,
                    onValueChange = { kcal = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.diary_calories)) },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroBasis.entries.forEach { option ->
                        SegmentButton(
                            label = stringResource(
                                if (option == MacroBasis.GRAMS) {
                                    R.string.diaryscreen_grams
                                } else {
                                    R.string.diaryscreen_percent
                                },
                            ),
                            selected = basis == option,
                            onClick = {
                                if (option != basis) {
                                    // The numbers move with the chip. Left where they were, 150
                                    // grams of protein becomes 150 per cent of the day, and what
                                    // gets stored is 750 grams.
                                    val total = LocaleNumbers.decimal(kcal)
                                    protein = TargetRevision.movedTo(
                                        protein, option, total,
                                        MacroTarget.KCAL_PER_GRAM_PROTEIN, LocaleNumbers::decimal,
                                    )
                                    carbs = TargetRevision.movedTo(
                                        carbs, option, total,
                                        MacroTarget.KCAL_PER_GRAM_CARBS, LocaleNumbers::decimal,
                                    )
                                    fat = TargetRevision.movedTo(
                                        fat, option, total,
                                        MacroTarget.KCAL_PER_GRAM_FAT, LocaleNumbers::decimal,
                                    )
                                    basis = option
                                }
                            },
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
                    label = { Text(stringResource(R.string.diary_protein_optional, suffix)) },
                )
                OutlinedTextField(
                    value = carbs,
                    onValueChange = { carbs = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.diary_carbohydrate_optional, suffix)) },
                )
                OutlinedTextField(
                    value = fat,
                    onValueChange = { fat = keepNumeric(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.diary_fat_optional, suffix)) },
                )
                Spacer(Modifier.height(6.dp))
                SegmentButton(
                    label = stringResource(
                        R.string.diary_only_on_a_day,
                        day.name.lowercase().replaceFirstChar(Char::uppercase),
                    ),
                    selected = justThisDay,
                    onClick = { justThisDay = !justThisDay },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // Eating the same on a rest day as on a long run is what this is for.
                    text = stringResource(R.string.diary_leave_that_off_and_this_is),
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
                        TargetRevision.rowFor(day, justThisDay),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun fieldFor(grams: Double?, percent: Double?, basis: MacroBasis): String {
    val value = if (basis == MacroBasis.GRAMS) grams else percent
    return value?.roundToInt()?.toString().orEmpty()
}
