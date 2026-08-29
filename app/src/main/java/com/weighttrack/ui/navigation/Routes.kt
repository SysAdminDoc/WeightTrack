package com.weighttrack.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
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
    HOME(Routes.HOME, "Home", Icons.Outlined.Home),
    CHARTS(Routes.CHARTS, "Charts", Icons.AutoMirrored.Outlined.ShowChart),
    HISTORY(Routes.HISTORY, "History", Icons.Outlined.History),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
}
