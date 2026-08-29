package com.weighttrack.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHARTS = "charts"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val GOAL = "goal"
    const val MEASUREMENTS = "measurements"
    const val LOG = "log"
    const val LOG_WITH_ARG = "log?entryId={entryId}"
    const val ENTRY_ID_ARG = "entryId"

    fun log(entryId: Long? = null): String =
        if (entryId == null) LOG else "log?entryId=$entryId"
}

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "Home", Icons.Filled.Insights),
    CHARTS(Routes.CHARTS, "Charts", Icons.Filled.ShowChart),
    HISTORY(Routes.HISTORY, "History", Icons.Outlined.History),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
}
