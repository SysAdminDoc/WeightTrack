package com.weighttrack.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.sync.WearSync
import com.weighttrack.core.sync.WearWeightLog
import com.weighttrack.data.repo.WeightRepository
import com.weighttrack.widget.SurfaceUpdater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.net.Uri
import java.time.Instant
import javax.inject.Inject

/**
 * Takes what the watch sends.
 *
 * Android starts this service for the delivery and stops it again, so the work runs on its own
 * scope rather than a lifecycle one. Readings arrive as data items, which the Data Layer holds
 * until they have been delivered, so one logged with the phone out of range is not lost.
 */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {

    @Inject lateinit var weightRepository: WeightRepository

    @Inject lateinit var surfaceUpdater: SurfaceUpdater

    @Inject lateinit var wearBridge: WearBridge

    @Inject lateinit var wearSummaryBuilder: WearSummaryBuilder

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearSync.PATH_REQUEST_SUMMARY) {
            scope.launch { wearBridge.publish(wearSummaryBuilder.current()) }
        } else {
            super.onMessageReceived(event)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is released the moment this returns, so everything needed is copied out
        // before any coroutine starts.
        val logged = events.mapNotNull { event ->
            val item = event.dataItem
            if (event.type != DataEvent.TYPE_CHANGED || !WearSync.isLogPath(item.uri.path)) {
                null
            } else {
                WearSync.decodeWeightLog(item.data)?.let { it to item.uri.toString() }
            }
        }
        if (logged.isEmpty()) return

        scope.launch {
            logged.forEach { (log, _) -> record(log) }
            surfaceUpdater.refresh()
            // Recorded, so the item has done its job. Left in place it would be redelivered
            // every time the watch reconnects.
            logged.forEach { (_, uri) ->
                runCatching {
                    Wearable.getDataClient(applicationContext)
                        .deleteDataItems(Uri.parse(uri))
                        .await()
                }
            }
        }
    }

    private suspend fun record(log: WearWeightLog) {
        if (log.grams <= 0) return
        // upsertByIdentity keys on the record id the watch generated, so the same reading
        // arriving twice stays one row.
        weightRepository.add(
            grams = log.grams,
            timestamp = Instant.ofEpochMilli(log.timestampUtcMillis),
            source = EntrySource.MANUAL,
            clientRecordId = log.clientRecordId,
        )
    }
}
