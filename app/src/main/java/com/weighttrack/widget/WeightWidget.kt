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
import com.weighttrack.ui.format.DateFormatters
import com.weighttrack.core.format.WeightFormatter
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
): WidgetData {
    if (appLockEnabled) {
        return WidgetData(trendGrams = null, unit = unit, weekChangeGrams = null, lastLogged = null, hidden = true)
    }
    if (series == null) {
        return WidgetData(trendGrams = null, unit = unit, weekChangeGrams = null, lastLogged = null)
    }
    return WidgetData(
        trendGrams = series.latestTrendGrams?.roundToInt(),
        unit = unit,
        // The same week the app itself shows. See TrendHeroCard.
        weekChangeGrams =
            com.weighttrack.core.math.Analytics.changeSinceWeekStart(series, rule, today),
        lastLogged = series.lastMeasured?.date,
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
        val series = if (daily.isEmpty()) null else TrendEngine.computeSeries(daily, settings.trendWindowDays)
        return buildWidgetData(
            appLockEnabled = settings.appLockEnabled,
            unit = settings.weightUnit,
            rule = settings.weekRule,
            series = series,
        )
    }

    companion object {
        /** Called after a reading changes so the widget does not sit on a stale number. */
        suspend fun refresh(context: Context) {
            runCatching { WeightWidget().updateAll(context) }
        }
    }
}

/**
 * A word from the resource file.
 *
 * Glance has no `stringResource`, so this is the equivalent. It exists so the widgets are
 * translated along with the rest of the app rather than staying in English on the home screen.
 */
@androidx.compose.runtime.Composable
private fun words(@androidx.annotation.StringRes id: Int, vararg arguments: Any): String =
    androidx.glance.LocalContext.current.getString(id, *arguments)

@androidx.compose.runtime.Composable
private fun WidgetContent(data: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = words(com.weighttrack.R.string.widget_trend),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(GlanceModifier.height(2.dp))

        if (data.hidden) {
            Text(
                text = words(com.weighttrack.R.string.widget_locked),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = words(com.weighttrack.R.string.widget_open_weighttrack),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
            return@Column
        }

        if (data.trendGrams == null) {
            Text(
                text = words(com.weighttrack.R.string.widget_tap_to_log),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(
                text = WeightFormatter.value(data.trendGrams, data.unit),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = " ${WeightFormatter.unitLabel(data.unit)}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        }

        data.weekChangeGrams?.let { change ->
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = words(com.weighttrack.R.string.widget_this_week, WeightFormatter.delta(change, data.unit)),
                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
            )
        }

        data.lastLogged?.let { date ->
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = words(com.weighttrack.R.string.widget_weighed, DateFormatters.sinceDay(date)),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

class WeightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeightWidget()
}
