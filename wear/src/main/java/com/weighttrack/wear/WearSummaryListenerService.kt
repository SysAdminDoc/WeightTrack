package com.weighttrack.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.weighttrack.core.sync.WearSync
import kotlinx.coroutines.runBlocking

/**
 * Files the figures the phone publishes.
 *
 * Runs whether or not the watch app is open, so the tile and the complication have something to
 * draw the moment someone raises their wrist. The write is blocking on purpose: Android is free
 * to stop this service as soon as the callback returns, and the buffer is released with it.
 */
class WearSummaryListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        val payload = events.lastOrNull { event ->
            event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearSync.PATH_SUMMARY
        }?.dataItem?.data ?: return

        runBlocking { WearSummaryStore(applicationContext).save(payload) }
    }
}
