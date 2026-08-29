package com.weighttrack.widget

import android.content.Context
import com.weighttrack.wear.WearBridge
import com.weighttrack.wear.WearSummaryBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes a fresh reading out to everything that shows one outside the app.
 *
 * That is the home screen widgets and a paired watch. A surface that keeps yesterday's number
 * until Android next feels like updating it is worse than no surface, so anything that changes
 * the log calls this straight after.
 */
@Singleton
class SurfaceUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wearBridge: WearBridge,
    private val wearSummaryBuilder: WearSummaryBuilder,
) {
    suspend fun refresh() {
        WeightWidget.refresh(context)
        WaterWidget.refresh(context)
        if (wearBridge.isSupported) {
            wearBridge.publish(wearSummaryBuilder.current())
        }
    }
}
