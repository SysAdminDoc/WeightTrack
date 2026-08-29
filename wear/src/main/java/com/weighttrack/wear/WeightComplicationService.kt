package com.weighttrack.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.flow.first

/**
 * The trend on a watch face.
 *
 * Answers from the cached summary, because a watch face asks whenever it feels like it and the
 * phone is often not there. Tapping opens the picker, so the complication is a shortcut as well
 * as a readout.
 */
class WeightComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        complication(type, "82.5 kg", "-0.4 kg", "82.5 kg, -0.4 kg this week")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val summary = WearSummaryStore(applicationContext).summary.first()
        return complication(
            type = request.complicationType,
            headline = WearGlanceText.headline(summary),
            shortDetail = WearGlanceText.shortDetail(summary),
            longText = WearGlanceText.headline(summary) + ", " + WearGlanceText.detail(summary),
        )
    }

    private fun complication(
        type: ComplicationType,
        headline: String,
        shortDetail: String,
        longText: String,
    ): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(headline).build(),
            contentDescription = PlainComplicationText.Builder(longText).build(),
        )
            .apply {
                // The title is dropped rather than left blank: an empty second line pushes the
                // weight into a smaller size on most faces for nothing.
                if (shortDetail.isNotEmpty()) {
                    setTitle(PlainComplicationText.Builder(shortDetail).build())
                }
            }
            .setTapAction(tapAction())
            .build()

        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(longText).build(),
            contentDescription = PlainComplicationText.Builder(longText).build(),
        )
            .setTapAction(tapAction())
            .build()

        // A face asking for a type this data cannot fill gets nothing rather than a guess.
        else -> null
    }

    private fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, WearMainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** Called when new figures land, because there is no useful period to poll on. */
        fun requestUpdate(context: android.content.Context) {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, WeightComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
