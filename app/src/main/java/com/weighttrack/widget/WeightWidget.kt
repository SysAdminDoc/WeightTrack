package com.weighttrack.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.action.actionStartActivity
import com.weighttrack.MainActivity
import com.weighttrack.core.math.TrendEngine
import com.weighttrack.core.math.TrendSeries
import com.weighttrack.core.model.WeightUnit
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WeightRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The widget reads through the same repositories the app does, so it can never show a figure
 * the app disagrees with.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun weightRepository(): WeightRepository
    fun settingsRepository(): SettingsRepository
}

internal data class WidgetData(
    val trendGrams: Int?,
    val unit: WeightUnit,
    val weekChangeGrams: Double?,
    val lastLogged: LocalDate?,
    /** True when the app lock is on, in which case the widget shows nothing readable. */
    val hidden: Boolean = false,
    /**
     * How far the last reading sat above or below the trend, signed, in grams.
     *
     * Only filled in for the glance mode. Null when the last day the person weighed carries no
     * reading of its own, which is every day after a gap.
     */
    val aboveTrendGrams: Double? = null,
    /** True when the widget may show a direction but never a weight. */
    val glanceOnly: Boolean = false,
)

/**
 * Decides what the widget may show.
 *
 * Separated from the Android plumbing so the one rule that matters can be tested: with the app
 * lock on, a widget that still printed the trend weight would put on the home screen exactly
 * what the lock exists to hide.
 */
internal fun buildWidgetData(
    appLockEnabled: Boolean,
    unit: WeightUnit,
    rule: com.weighttrack.core.math.WeekRule,
    series: TrendSeries?,
    today: java.time.LocalDate = java.time.LocalDate.now(),
    /** See `AppSettings.glanceOnlySurfaces`. */
    glanceOnly: Boolean = false,
): WidgetData {
    if (appLockEnabled) {
        return WidgetData(trendGrams = null, unit = unit, weekChangeGrams = null, lastLogged = null, hidden = true)
    }
    if (series == null) {
        return WidgetData(
            trendGrams = null,
            unit = unit,
            weekChangeGrams = null,
            lastLogged = null,
            glanceOnly = glanceOnly,
        )
    }
    val measured = series.lastMeasured
    if (glanceOnly) {
        // Every absolute figure is left out here rather than hidden in the drawing, so there is
        // no branch anywhere further on that could put a weight back on the home screen.
        return WidgetData(
            trendGrams = null,
            unit = unit,
            weekChangeGrams = null,
            lastLogged = measured?.date,
            aboveTrendGrams = measured?.actualGrams?.let { it - measured.trendGrams },
            glanceOnly = true,
        )
    }
    return WidgetData(
        trendGrams = series.latestTrendGrams?.roundToInt(),
        unit = unit,
        // The same week the app itself shows. See TrendHeroCard.
        weekChangeGrams =
            com.weighttrack.core.math.Analytics.changeSinceWeekStart(series, rule, today),
        lastLogged = measured?.date,
    )
}

class WeightWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadData(context)
        provideContent {
            GlanceTheme {
                WidgetContent(data)
            }
        }
    }

    private suspend fun loadData(context: Context): WidgetData {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val settings = entryPoint.settingsRepository().settings.first()
        val daily = entryPoint.weightRepository().observeDailyWeights().first()
        val series = if (daily.isEmpty()) null else TrendEngine.computeSeries(daily, settings.trendWindowDays, settings.smoothingMode)
        return buildWidgetData(
            appLockEnabled = settings.appLockEnabled,
            unit = settings.weightUnit,
            rule = settings.weekRule,
            series = series,
            glanceOnly = settings.glanceOnlySurfaces,
        )
    }

    companion object {
        /** Called after a reading changes so the widget does not sit on a stale number. */
        suspend fun refresh(context: Context) {
            runCatching { WeightWidget().updateAll(context) }
        }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetContent(data: WidgetData) {
    val lines = widgetLines(androidx.glance.LocalContext.current, data)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = lines.caption,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(2.dp))

        // A phrase where a figure would be, so it is set at reading size rather than at the size
        // a two-digit weight wants.
        if (lines.unit == null) {
            Text(
                text = lines.value,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        } else {
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text(
                    text = lines.value,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = " " + lines.unit,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                )
            }
        }

        lines.change?.let { change ->
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = change,
                style = TextStyle(
                    color = if (lines.unit == null) {
                        GlanceTheme.colors.onSurfaceVariant
                    } else {
                        GlanceTheme.colors.primary
                    },
                    fontSize = if (lines.unit == null) 11.sp else 12.sp,
                ),
            )
        }

        lines.logged?.let { logged ->
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = logged,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

class WeightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeightWidget()
}
