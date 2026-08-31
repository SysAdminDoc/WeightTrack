package com.weighttrack.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.health.connect.client.PermissionController
import androidx.camera.core.ImageProxy
import com.weighttrack.barcode.BarcodeReader
import com.weighttrack.core.math.TrendPoint
import com.weighttrack.core.math.TrendRate
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.EntryTag
import com.weighttrack.core.model.Goal
import com.weighttrack.core.model.GoalDirection
import com.weighttrack.core.model.HealthDirection
import com.weighttrack.core.model.MeasurementType
import com.weighttrack.core.model.UserProfile
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.prefs.AppSettings
import com.weighttrack.data.repo.Profile
import com.weighttrack.data.sync.SyncSettings
import com.weighttrack.domain.ProgressSnapshot
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.ui.barcode.ScanScreen
import com.weighttrack.ui.charts.ActivityState
import com.weighttrack.ui.charts.ActivityStatus
import com.weighttrack.ui.charts.ChartsScreen
import com.weighttrack.ui.diagnostics.CrashLogScreen
import com.weighttrack.ui.diagnostics.CrashLogUiState
import com.weighttrack.ui.diary.DiaryScreen
import com.weighttrack.ui.diary.DiaryUiState
import com.weighttrack.ui.fasting.FastingScreen
import com.weighttrack.ui.fasting.FastingUiState
import com.weighttrack.ui.food.FoodScreen
import com.weighttrack.ui.food.FoodUiState
import com.weighttrack.ui.goal.GoalScreen
import com.weighttrack.ui.goal.GoalUiState
import com.weighttrack.ui.health.HealthRationaleScreen
import com.weighttrack.ui.history.HistoryScreen
import com.weighttrack.ui.history.HistoryUiState
import com.weighttrack.ui.home.HomeScreen
import com.weighttrack.ui.home.WaterSummary
import com.weighttrack.ui.lock.LockScreen
import com.weighttrack.ui.log.LogWeightScreen
import com.weighttrack.ui.log.LogWeightUiState
import com.weighttrack.ui.measurements.MeasurementsScreen
import com.weighttrack.ui.measurements.MeasurementsUiState
import com.weighttrack.ui.onboarding.OnboardingScreen
import com.weighttrack.ui.onboarding.OnboardingUiState
import com.weighttrack.ui.onboarding.OnboardingViewModel
import com.weighttrack.ui.photos.PhotosScreen
import com.weighttrack.ui.photos.PhotosUiState
import com.weighttrack.ui.scale.ScaleScreen
import com.weighttrack.ui.scale.ScaleStage
import com.weighttrack.ui.scale.ScaleUiState
import com.weighttrack.ui.settings.HealthConnectState
import com.weighttrack.ui.settings.PendingRestore
import com.weighttrack.ui.settings.SettingsScreen
import com.weighttrack.ui.settings.SettingsViewModel
import com.weighttrack.ui.water.WaterScreen
import com.weighttrack.ui.water.WaterUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One screen in one state, ready to render.
 *
 * The states are the ones nobody looks at: an empty screen before anything has been recorded, a
 * screen still loading, one where a permission has been refused, one showing a recoverable error,
 * and one with an undo waiting to be taken. Screenshots have only ever been taken of full, happy,
 * dark screens in English, and those are the states least likely to be wrong.
 */
internal data class ScreenFixture(
    val screen: String,
    val state: String,
    val content: @Composable () -> Unit,
) {
    override fun toString(): String = "$screen, $state"
}

/** Today, fixed, so a fixture renders the same thing in December as in August. */
private val TODAY: LocalDate = LocalDate.of(2026, 8, 29)
private val NOON: Instant = Instant.parse("2026-08-29T12:00:00Z")

private object FixtureBarcodeReader : BarcodeReader {
    override val name: String = "test reader"

    override suspend fun read(image: ImageProxy): String? = null
}

/**
 * Settings owns launchers and lifecycle work, so the screen needs its real collaborator shape.
 * A constructor-free mock keeps the fixture deterministic and makes every flow the screen reads
 * explicit. No service, scheduler, database, camera, or Health Connect provider is started.
 */
private fun settingsViewModel(): SettingsViewModel {
    val health = mock(HealthConnectSync::class.java)
    doReturn(PermissionController.createRequestPermissionResultContract())
        .`when`(health).permissionContract()
    doReturn(emptySet<String>()).`when`(health).grantablePermissions(HealthDirection.TWO_WAY)

    return mock(SettingsViewModel::class.java).also { viewModel ->
        doReturn(health).`when`(viewModel).healthConnect
        doReturn(MutableStateFlow(UserProfile())).`when`(viewModel).demographics
        doReturn(MutableStateFlow(SyncSettings())).`when`(viewModel).syncSettings
        doReturn(MutableStateFlow(false)).`when`(viewModel).syncing
        doReturn(MutableStateFlow(SettingsViewModel.AutoBackupState()))
            .`when`(viewModel).autoBackup
        doReturn(MutableStateFlow(0)).`when`(viewModel).crashReportCount
        doReturn(MutableStateFlow<PendingRestore?>(null)).`when`(viewModel).pendingRestore
    }
}

private fun entry(daysAgo: Long, grams: Int) = WeightEntry(
    id = daysAgo + 1,
    timestamp = NOON.minusSeconds(daysAgo * 86_400),
    zoneOffset = ZoneOffset.UTC,
    localDate = TODAY.minusDays(daysAgo),
    grams = grams,
    source = EntrySource.MANUAL,
    clientRecordId = "e-$daysAgo",
    tags = setOf(EntryTag.TRAVEL),
)

private fun filledSnapshot(): ProgressSnapshot {
    val points = (0 until 30).map { day ->
        val grams = 84_000.0 - day * 40
        TrendPoint(
            date = TODAY.minusDays((29 - day).toLong()),
            trendGrams = grams,
            actualGrams = grams.toInt(),
        )
    }
    return ProgressSnapshot.empty(AppSettings()).copy(
        entryCount = 30,
        latestEntry = entry(0, 82_800),
        series = TrendSeries(points, 0.1),
        rate = TrendRate(gramsPerDay = -40.0, standardErrorGramsPerDay = 8.0, sampleDays = 30),
        goal = Goal(
            id = 1,
            direction = GoalDirection.LOSE,
            startGrams = 90_000,
            targetGrams = 78_000,
            startDate = TODAY.minusDays(120),
            targetDate = TODAY.plusDays(90),
            milestoneStepGrams = 1_000,
        ),
        bmi = 26.4,
    )
}

/**
 * Every screen the app can show, in the states worth checking.
 *
 * `ScreenCoverageTest` holds this list to the screens that actually exist, so a screen added
 * later cannot quietly miss the check.
 */
internal object ScreenFixtures {

    val all: List<ScreenFixture> = listOf(
        ScreenFixture("OnboardingScreen", "empty") {
            OnboardingScreen(
                state = OnboardingUiState(),
                viewModel = remember { mock(OnboardingViewModel::class.java) },
            )
        },
        ScreenFixture("HomeScreen", "empty") {
            HomeScreen(
                snapshot = ProgressSnapshot.empty(AppSettings()),
                onLogWeight = {},
                onOpenGoal = {},
                onOpenMeasurements = {},
                onOpenWater = {},
                onOpenFasting = {},
                onOpenPhotos = {},
                onOpenScale = {},
                onOpenFoods = {},
                onOpenDiary = {},
                nutritionEnabled = false,
                waterSummary = null,
                today = TODAY,
            )
        },
        ScreenFixture("HomeScreen", "filled") {
            HomeScreen(
                snapshot = filledSnapshot(),
                onLogWeight = {},
                onOpenGoal = {},
                onOpenMeasurements = {},
                onOpenWater = {},
                onOpenFasting = {},
                onOpenPhotos = {},
                onOpenScale = {},
                onOpenFoods = {},
                onOpenDiary = {},
                nutritionEnabled = true,
                waterSummary = WaterSummary(1_250, 2_000, VolumeUnit.ML),
                today = TODAY,
            )
        },
        ScreenFixture("HistoryScreen", "empty") {
            HistoryScreen(
                state = HistoryUiState(),
                onQueryChange = {},
                onToggleSelection = {},
                onClearSelection = {},
                onSelectAll = {},
                onDeleteSelected = {},
                onEdit = {},
                today = TODAY,
            )
        },
        ScreenFixture("HistoryScreen", "selecting") {
            HistoryScreen(
                state = HistoryUiState(
                    entries = listOf(entry(0, 82_800), entry(1, 83_100), entry(2, 83_400)),
                    selectedIds = setOf(1L, 2L),
                ),
                onQueryChange = {},
                onToggleSelection = {},
                onClearSelection = {},
                onSelectAll = {},
                onDeleteSelected = {},
                onEdit = {},
                today = TODAY,
            )
        },
        ScreenFixture("ChartsScreen", "empty") {
            ChartsScreen(
                snapshot = ProgressSnapshot.empty(AppSettings()),
                activity = ActivityState(status = ActivityStatus.NOT_PERMITTED),
                today = TODAY,
            )
        },
        ScreenFixture("ChartsScreen", "loading") {
            ChartsScreen(
                snapshot = filledSnapshot(),
                activity = ActivityState(status = ActivityStatus.LOADING),
                today = TODAY,
            )
        },
        ScreenFixture("GoalScreen", "empty") {
            GoalScreen(
                state = GoalUiState(),
                milestoneOptions = listOf("1 kg" to 1_000, "2.5 kg" to 2_500),
                onDigit = {},
                onBackspace = {},
                onClear = {},
                onTargetDateChange = {},
                onMilestoneStepChange = {},
                onSave = {},
                onClearGoal = {},
                onClose = {},
                today = TODAY,
            )
        },
        ScreenFixture("LogWeightScreen", "empty") {
            LogWeightScreen(
                state = LogWeightUiState(date = TODAY),
                onDigit = {},
                onBackspace = {},
                onClear = {},
                onDateChange = {},
                onTimeChange = {},
                onNoteChange = {},
                onBodyFatChange = {},
                onToggleTag = {},
                onSave = {},
                onClose = {},
            )
        },
        ScreenFixture("MeasurementsScreen", "empty") {
            MeasurementsScreen(
                state = MeasurementsUiState(),
                editor = null,
                onStartEditing = {},
                onEditorTextChange = {},
                onCancelEditing = {},
                onSaveEditor = {},
                onClose = {},
                today = TODAY,
            )
        },
        ScreenFixture("WaterScreen", "empty") {
            WaterScreen(
                state = WaterUiState(date = TODAY),
                onAddServing = {},
                onAdd = {},
                onRemove = {},
                onClearDay = {},
                onPreviousDay = {},
                onNextDay = {},
                onSetTarget = {},
                onSetServing = {},
                onBack = {},
                today = TODAY,
            )
        },
        ScreenFixture("FastingScreen", "error") {
            FastingScreen(
                state = FastingUiState(),
                now = remember { mutableStateOf(NOON) },
                onSelectPreset = {},
                onStart = {},
                onStop = {},
                onCancel = {},
                onDelete = {},
                editing = null,
                onStartEditing = {},
                onCancelEditing = {},
                onSaveEdit = { _, _ -> },
                message = "That fast could not be saved. Try again.",
                onDismissMessage = {},
                onBack = {},
                today = TODAY,
            )
        },
        ScreenFixture("FoodScreen", "empty") {
            FoodScreen(
                state = FoodUiState(),
                onQueryChange = {},
                onSearchOnline = {},
                onKeep = {},
                onFavourite = { _, _ -> },
                onDelete = {},
                onAddCustom = { _, _, _, _, _, _, _ -> },
                onDeleteRecipe = {},
                onScan = {},
                onSetUsdaKey = {},
                onDismissMessage = {},
                onBack = {},
            )
        },
        ScreenFixture("DiaryScreen", "empty") {
            DiaryScreen(
                state = DiaryUiState(date = TODAY),
                suggestedMeal = com.weighttrack.core.nutrition.Meal.BREAKFAST,
                onPreviousDay = {},
                onNextDay = {},
                onQueryChange = {},
                onLog = { _, _, _ -> },
                onQuickAdd = { _, _, _ -> },
                onCopyYesterday = {},
                onSetTarget = { _, _, _, _, _, _ -> },
                onUseRecommendation = {},
                onDelete = {},
                onDismissMessage = {},
                onBack = {},
                today = TODAY,
            )
        },
        ScreenFixture("PhotosScreen", "undo") {
            PhotosScreen(
                state = PhotosUiState(),
                onToggleSelection = {},
                onClearSelection = {},
                onImport = {},
                onPrepareCapture = { File("photo.jpg") },
                onCaptureResult = {},
                onDelete = {},
                onBack = {},
                message = "Photo deleted",
                onDismissMessage = {},
                today = TODAY,
            )
        },
        ScreenFixture("ScaleScreen", "permission refused") {
            ScaleScreen(
                state = ScaleUiState(
                    stage = ScaleStage.BLOCKED,
                    problem = com.weighttrack.ble.ScaleProblem.PERMISSION_MISSING,
                ),
                permissions = listOf("android.permission.BLUETOOTH_SCAN"),
                onRetry = {},
                onConnect = {},
                onSave = {},
                onSaveToSuggested = {},
                onSaveTo = {},
                onDiscard = {},
                onForgetScale = {},
                onBack = {},
            )
        },
        ScreenFixture("CrashLogScreen", "empty") {
            CrashLogScreen(
                state = CrashLogUiState(),
                onOpen = {},
                onClose = {},
                onDelete = {},
                onDeleteAll = {},
                onShareActivityLog = {},
                onBack = {},
            )
        },
        ScreenFixture("HealthRationaleScreen", "shown") {
            HealthRationaleScreen(onBack = {})
        },
        ScreenFixture("SettingsScreen", "empty") {
            SettingsScreen(
                settings = AppSettings(),
                profiles = listOf(Profile(1, "You", 0)),
                activeProfileId = 1,
                entryCount = 0,
                healthConnectState = HealthConnectState(),
                busy = false,
                viewModel = remember { settingsViewModel() },
                onOpenCrashLogs = {},
                onOpenHealthRationale = {},
            )
        },
        ScreenFixture("ScanScreen", "permission refused") {
            ScanScreen(
                reader = FixtureBarcodeReader,
                onScanned = {},
                onBack = {},
            )
        },
        ScreenFixture("LockScreen", "error") {
            LockScreen(error = "That did not unlock. Try again.", onUnlock = {})
        },
    )
}
