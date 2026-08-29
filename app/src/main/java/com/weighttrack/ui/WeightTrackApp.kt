package com.weighttrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.weighttrack.ui.charts.ChartsScreen
import com.weighttrack.ui.components.ResumeEffect
import com.weighttrack.ui.charts.ChartsViewModel
import com.weighttrack.ui.fasting.FastingScreen
import com.weighttrack.ui.fasting.FastingViewModel
import com.weighttrack.ui.diagnostics.CrashLogScreen
import com.weighttrack.ui.diagnostics.CrashLogViewModel
import com.weighttrack.ui.goal.GoalScreen
import com.weighttrack.ui.goal.GoalViewModel
import com.weighttrack.ui.history.HistoryScreen
import com.weighttrack.ui.history.HistoryViewModel
import com.weighttrack.ui.home.HomeScreen
import com.weighttrack.ui.home.HomeViewModel
import com.weighttrack.ui.log.LogWeightScreen
import com.weighttrack.ui.log.LogWeightViewModel
import com.weighttrack.ui.measurements.MeasurementsScreen
import com.weighttrack.ui.measurements.MeasurementsViewModel
import com.weighttrack.ui.navigation.Routes
import com.weighttrack.ui.photos.PhotosScreen
import com.weighttrack.ui.food.FoodScreen
import com.weighttrack.ui.food.FoodViewModel
import com.weighttrack.ui.photos.PhotosViewModel
import com.weighttrack.ui.scale.ScaleScreen
import com.weighttrack.ui.scale.ScaleViewModel
import com.weighttrack.ui.navigation.TopLevelDestination
import com.weighttrack.ui.onboarding.OnboardingScreen
import com.weighttrack.ui.onboarding.OnboardingViewModel
import com.weighttrack.ui.settings.SettingsScreen
import com.weighttrack.ui.settings.SettingsViewModel
import com.weighttrack.ui.water.WaterScreen
import com.weighttrack.ui.water.WaterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightTrackApp(
    onboardingComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    if (!onboardingComplete) {
        val viewModel: OnboardingViewModel = hiltViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        OnboardingScreen(state = state, viewModel = viewModel, modifier = modifier)
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevel = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }
    val isFullScreenRoute = currentRoute == Routes.LOG_WITH_ARG ||
        currentRoute == Routes.GOAL ||
        currentRoute == Routes.MEASUREMENTS ||
        currentRoute == Routes.CRASH_LOGS ||
        currentRoute == Routes.WATER ||
        currentRoute == Routes.FASTING ||
        currentRoute == Routes.PHOTOS ||
        currentRoute == Routes.SCALE ||
        currentRoute == Routes.FOODS

    Scaffold(
        modifier = modifier,
        topBar = {
            if (topLevel != null) {
                TopAppBar(
                    title = { Text(topLevel.label, style = MaterialTheme.typography.headlineMedium) },
                    actions = {
                        if (topLevel == TopLevelDestination.HOME) {
                            Row(
                                modifier = Modifier.padding(end = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "Privacy first",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (!isFullScreenRoute) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                ) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Routes.HISTORY) {
                FloatingActionButton(
                    onClick = { navController.navigate(Routes.log()) },
                    shape = RoundedCornerShape(8.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 2.dp,
                    ),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Log weight")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                val viewModel: HomeViewModel = hiltViewModel()
                val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
                val waterSummary by viewModel.waterSummary.collectAsStateWithLifecycle()
                HomeScreen(
                    snapshot = snapshot,
                    onLogWeight = { navController.navigate(Routes.log()) },
                    onOpenGoal = { navController.navigate(Routes.GOAL) },
                    onOpenMeasurements = { navController.navigate(Routes.MEASUREMENTS) },
                    onOpenWater = { navController.navigate(Routes.WATER) },
                    onOpenFasting = { navController.navigate(Routes.FASTING) },
                    onOpenPhotos = { navController.navigate(Routes.PHOTOS) },
                    onOpenScale = { navController.navigate(Routes.SCALE) },
                    onOpenFoods = { navController.navigate(Routes.FOODS) },
                    nutritionEnabled = snapshot.settings.nutritionEnabled,
                    waterSummary = waterSummary,
                )
            }

            composable(Routes.CHARTS) {
                val viewModel: ChartsViewModel = hiltViewModel()
                val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
                val activity by viewModel.activity.collectAsStateWithLifecycle()
                // A steps permission granted in Settings has to take effect on return.
                ResumeEffect { viewModel.onScreenResumed() }
                ChartsScreen(snapshot = snapshot, activity = activity)
            }

            composable(Routes.HISTORY) {
                val viewModel: HistoryViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val undoCount by viewModel.undoAvailable.collectAsStateWithLifecycle()

                // Deletion is immediate with an undo, never a confirmation dialog.
                LaunchedEffect(undoCount) {
                    if (undoCount > 0) {
                        val label = if (undoCount == 1) "Reading deleted" else "$undoCount readings deleted"
                        val result = snackbarHostState.showSnackbar(
                            message = label,
                            actionLabel = "Undo",
                            duration = androidx.compose.material3.SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoDelete()
                        } else {
                            viewModel.consumeUndo()
                        }
                    }
                }

                HistoryScreen(
                    state = state,
                    onQueryChange = viewModel::onQueryChange,
                    onToggleSelection = viewModel::toggleSelection,
                    onClearSelection = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onDeleteSelected = viewModel::deleteSelected,
                    onEdit = { entry -> navController.navigate(Routes.log(entry.id)) },
                )
            }

            composable(Routes.SETTINGS) {
                val viewModel: SettingsViewModel = hiltViewModel()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val entryCount by viewModel.entryCount.collectAsStateWithLifecycle()
                val healthConnectState by viewModel.healthConnectState.collectAsStateWithLifecycle()
                val busy by viewModel.busy.collectAsStateWithLifecycle()
                val message by viewModel.message.collectAsStateWithLifecycle()

                LaunchedEffect(message) {
                    message?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.consumeMessage()
                    }
                }

                val profiles by viewModel.profiles.collectAsStateWithLifecycle()
                val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
                SettingsScreen(
                    settings = settings,
                    profiles = profiles,
                    activeProfileId = activeProfileId,
                    entryCount = entryCount,
                    healthConnectState = healthConnectState,
                    busy = busy,
                    viewModel = viewModel,
                    onOpenCrashLogs = { navController.navigate(Routes.CRASH_LOGS) },
                )
            }

            composable(Routes.WATER) {
                val viewModel: WaterViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                WaterScreen(
                    state = state,
                    onAddServing = viewModel::addServing,
                    onAdd = viewModel::add,
                    onRemove = viewModel::remove,
                    onClearDay = viewModel::clearDay,
                    onPreviousDay = viewModel::showPreviousDay,
                    onNextDay = viewModel::showNextDay,
                    onSetTarget = viewModel::setTarget,
                    onSetServing = viewModel::setServing,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.FASTING) {
                val viewModel: FastingViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val editingFast by viewModel.editing.collectAsStateWithLifecycle()
                // Deliberately not delegated: reading the clock here would recompose the
                // whole fasting screen once a second instead of just the timer card.
                val fastingNow = viewModel.now.collectAsStateWithLifecycle()
                val fastingMessage by viewModel.message.collectAsStateWithLifecycle()
                FastingScreen(
                    state = state,
                    now = fastingNow,
                    onSelectPreset = viewModel::selectPreset,
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onCancel = viewModel::cancel,
                    onDelete = viewModel::delete,
                    editing = editingFast,
                    onStartEditing = viewModel::startEditing,
                    onCancelEditing = viewModel::cancelEditing,
                    onSaveEdit = viewModel::saveEdit,
                    message = fastingMessage,
                    onDismissMessage = viewModel::dismissMessage,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.FOODS) {
                val viewModel: FoodViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                FoodScreen(
                    state = state,
                    onQueryChange = viewModel::setQuery,
                    onSearchOnline = viewModel::searchOnline,
                    onKeep = viewModel::keep,
                    onFavourite = viewModel::setFavourite,
                    onDelete = viewModel::delete,
                    onAddCustom = viewModel::addCustom,
                    onDeleteRecipe = viewModel::deleteRecipe,
                    onSetUsdaKey = viewModel::setUsdaKey,
                    onDismissMessage = viewModel::dismissMessage,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SCALE) {
                val viewModel: ScaleViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                ScaleScreen(
                    state = state,
                    permissions = viewModel.requiredPermissions,
                    onRetry = viewModel::start,
                    onConnect = viewModel::connectTo,
                    onSave = viewModel::save,
                    onSaveToSuggested = viewModel::saveToSuggested,
                    onDiscard = viewModel::discard,
                    onForgetScale = viewModel::forgetScale,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.PHOTOS) {
                val viewModel: PhotosViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                PhotosScreen(
                    state = state,
                    onToggleSelection = viewModel::toggleSelection,
                    onClearSelection = viewModel::clearSelection,
                    onImport = viewModel::importFrom,
                    onPrepareCapture = viewModel::prepareCapture,
                    onCaptureResult = viewModel::onCaptureResult,
                    onDelete = viewModel::delete,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.CRASH_LOGS) {
                val viewModel: CrashLogViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                CrashLogScreen(
                    state = state,
                    onOpen = viewModel::open,
                    onClose = viewModel::close,
                    onDelete = viewModel::delete,
                    onDeleteAll = viewModel::deleteAll,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.LOG_WITH_ARG,
                arguments = listOf(
                    navArgument(Routes.ENTRY_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                val viewModel: LogWeightViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                LogWeightScreen(
                    state = state,
                    onDigit = viewModel::onDigit,
                    onBackspace = viewModel::onBackspace,
                    onClear = viewModel::onClear,
                    onDateChange = viewModel::onDateChange,
                    onTimeChange = viewModel::onTimeChange,
                    onNoteChange = viewModel::onNoteChange,
                    onBodyFatChange = viewModel::onBodyFatChange,
                    onToggleTag = viewModel::toggleTag,
                    onSave = viewModel::save,
                    onClose = { navController.popBackStack() },
                )
            }

            composable(Routes.GOAL) {
                val viewModel: GoalViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                GoalScreen(
                    state = state,
                    milestoneOptions = viewModel.milestoneOptions(),
                    onDigit = viewModel::onDigit,
                    onBackspace = viewModel::onBackspace,
                    onClear = viewModel::onClear,
                    onTargetDateChange = viewModel::onTargetDateChange,
                    onMilestoneStepChange = viewModel::onMilestoneStepChange,
                    onSave = viewModel::save,
                    onClearGoal = viewModel::clearGoal,
                    onClose = { navController.popBackStack() },
                )
            }

            composable(Routes.MEASUREMENTS) {
                val viewModel: MeasurementsViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val editor by viewModel.editor.collectAsStateWithLifecycle()
                MeasurementsScreen(
                    state = state,
                    editor = editor,
                    onStartEditing = viewModel::startEditing,
                    onEditorTextChange = viewModel::onEditorTextChange,
                    onCancelEditing = viewModel::cancelEditing,
                    onSaveEditor = viewModel::saveEditor,
                    onClose = { navController.popBackStack() },
                )
            }
        }
    }
}

/** Kept so the navigation graph has a single place to reach the controller in tests. */
internal fun NavHostController.openLog(entryId: Long? = null) {
    navigate(Routes.log(entryId))
}
