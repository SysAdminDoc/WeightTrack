package com.weighttrack.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.weighttrack.R

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHARTS = "charts"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val GOAL = "goal"
    const val MEASUREMENTS = "measurements"
    const val CRASH_LOGS = "crash-logs"
    const val WATER = "water"
    const val FASTING = "fasting"
    const val PHOTOS = "photos"
    const val SCALE = "scale"
    const val FOODS = "foods"
    const val SCAN = "scan"
    const val DIARY = "diary"
    const val HEALTH_RATIONALE = "health-rationale"
    const val LOG = "log"
    const val LOG_WITH_ARG = "log?entryId={entryId}"
    const val ENTRY_ID_ARG = "entryId"

    fun log(entryId: Long? = null): String =
        if (entryId == null) LOG else "log?entryId=$entryId"
}

enum class TopLevelDestination(
    val route: String,
    /** The resource rather than the word, so the bar can be translated like everything else. */
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Outlined.Home),
    CHARTS(Routes.CHARTS, R.string.nav_charts, Icons.AutoMirrored.Outlined.ShowChart),
    HISTORY(Routes.HISTORY, R.string.nav_history, Icons.Outlined.History),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings),
}
