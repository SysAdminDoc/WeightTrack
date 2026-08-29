package com.weighttrack.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes a fresh reading out to the home screen.
 *
 * A widget that keeps yesterday's number until Android next feels like updating it is worse
 * than no widget, so anything that changes the log calls this straight after.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun refresh() {
        WeightWidget.refresh(context)
    }
}
