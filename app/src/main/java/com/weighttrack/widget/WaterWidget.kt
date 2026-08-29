package com.weighttrack.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.weighttrack.core.model.VolumeUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WaterRepository
import com.weighttrack.health.HealthConnectSync
import com.weighttrack.ui.water.WaterLogger
import com.weighttrack.ui.format.VolumeFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WaterWidgetEntryPoint {
    fun waterRepository(): WaterRepository
    fun settingsRepository(): SettingsRepository
    fun healthConnect(): HealthConnectSync
}

private data class WaterWidgetData(
    val totalMl: Int,
    val targetMl: Int,
    val servingMl: Int,
    val unit: VolumeUnit,
)

private suspend fun loadWaterData(context: Context): WaterWidgetData {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        WaterWidgetEntryPoint::class.java,
    )
    val settings = entryPoint.settingsRepository().settings.first()
    val total = entryPoint.waterRepository().totalForDate(LocalDate.now())
    return WaterWidgetData(
        totalMl = total,
        targetMl = settings.waterTargetMl,
        servingMl = settings.waterServingMl,
        unit = VolumeUnit.forWeightUnit(settings.weightUnit),
    )
}

/**
 * Adds one serving straight from the home screen.
 *
 * The whole reason a water widget exists is that opening an app to record a glass of water is
 * more effort than the act being recorded, so this does the write and refreshes in place
 * without ever showing a screen.
 */
class AddWaterServingAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: androidx.glance.action.ActionParameters,
    ) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WaterWidgetEntryPoint::class.java,
        )
        val serving = entryPoint.settingsRepository().settings.first().waterServingMl
        // The same path the screen uses, so the widget is not a lesser way to log:
        // it lands on the right day and reaches Health Connect too.
        WaterLogger.log(
            millilitres = serving,
            onDate = LocalDate.now(),
            waterRepository = entryPoint.waterRepository(),
            healthConnect = entryPoint.healthConnect(),
        )
        WaterWidget().updateAll(context)
    }
}

class WaterWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWaterData(context)
        provideContent {
            GlanceTheme { WaterWidgetContent(data) }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching { WaterWidget().updateAll(context) }
        }
    }
}

@Composable
private fun WaterWidgetContent(data: WaterWidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
            .clickable(actionRunCallback<AddWaterServingAction>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = "WATER",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(2.dp))
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(
                text = VolumeFormatter.value(data.totalMl, data.unit),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = " / ${VolumeFormatter.full(data.targetMl, data.unit)}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        Text(
            text = "Tap to add ${VolumeFormatter.full(data.servingMl, data.unit)}",
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
        )
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}
