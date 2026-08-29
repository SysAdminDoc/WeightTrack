package com.weighttrack.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The tile: the trend at a glance, and one tap to log a weight.
 *
 * Drawn from the cached summary rather than the phone. A tile is rendered when someone swipes,
 * not when the phone is in range, so anything that waited on a round trip would show an empty
 * card on a walk.
 *
 * Built from the plain layout elements rather than the material helpers, so the tile keeps
 * rendering the same across releases of those helpers.
 */
class WeightTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { completer ->
        scope.launch {
            // A datastore read throws rather than emits on a corrupt or unreadable file. Left
            // uncaught, the coroutine dies and the future is never completed, which the
            // carousel shows as a permanently blank card with nothing in the log.
            val summary = runCatching {
                WearSummaryStore(applicationContext).summary.first()
            }.getOrNull()
            completer.set(
                weightTile(
                    headline = WearGlanceText.headline(applicationContext, summary),
                    detail = WearGlanceText.detail(applicationContext, summary),
                    packageName = packageName,
                    tapToLog = getString(R.string.wear_tap_to_log),
                ),
            )
        }
        "WeightTrack tile"
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
        "WeightTrack tile resources"
    }
}

private const val RESOURCES_VERSION = "1"
private const val HEADLINE_COLOR = 0xFFFFFFFF.toInt()
private const val DETAIL_COLOR = 0xFFB4C0CE.toInt()
private const val ACCENT_COLOR = 0xFF6EE7B7.toInt()

/**
 * The tile itself.
 *
 * A function rather than a method on the service so it can be built and read in a test. The
 * protolayout builders throw on a malformed tile, and a tile that throws renders as an empty
 * card in the carousel with nothing in the log to say why.
 */
internal fun weightTile(
    headline: String,
    detail: String,
    packageName: String,
    tapToLog: String,
): TileBuilders.Tile = TileBuilders.Tile.Builder()
    .setResourcesVersion(RESOURCES_VERSION)
    // The figures change when a reading is logged, not on a clock, so the tile is refreshed on
    // demand rather than polled.
    .setFreshnessIntervalMillis(0)
    .setTileTimeline(
        TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(
                        LayoutElementBuilders.Layout.Builder()
                            .setRoot(weightTileLayout(headline, detail, packageName, tapToLog))
                            .build(),
                    )
                    .build(),
            )
            .build(),
    )
    .build()

private fun weightTileLayout(
    headline: String,
    detail: String,
    packageName: String,
    tapToLog: String,
): LayoutElementBuilders.LayoutElement = LayoutElementBuilders.Box.Builder()
    .setWidth(expand())
    .setHeight(expand())
    .setModifiers(
        ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                // The whole tile is the target. A small button is a poor thing to hit on a
                // wrist, and there is only one thing to do here.
                ModifiersBuilders.Clickable.Builder()
                    .setId("log")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName(WearMainActivity::class.java.name)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(12f)).build())
            .build(),
    )
    .addContent(
        LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(tileText(headline, sizeSp = 28f, color = HEADLINE_COLOR))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(tileText(detail, sizeSp = 13f, color = DETAIL_COLOR, maxLines = 2))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(10f)).build())
            .addContent(tileText(tapToLog, sizeSp = 13f, color = ACCENT_COLOR))
            .build(),
    )
    .build()

private fun tileText(
    text: String,
    sizeSp: Float,
    color: Int,
    maxLines: Int = 1,
): LayoutElementBuilders.Text = LayoutElementBuilders.Text.Builder()
    .setText(text)
    .setMaxLines(maxLines)
    .setFontStyle(
        LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setColor(argb(color))
            .build(),
    )
    .build()
