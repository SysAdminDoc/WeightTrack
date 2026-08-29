package com.weighttrack.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.weighttrack.core.sync.WearSummary
import com.weighttrack.core.sync.WearSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the summary through the Wear Data Layer.
 *
 * A data item rather than a message, because the watch has to be able to draw its tile with the
 * phone out of range. Google Play services keeps the last item on the watch until it is
 * replaced, which is exactly the behaviour a glanceable surface needs.
 */
@Singleton
class PlayWearBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : WearBridge {

    override val isSupported: Boolean = true

    override suspend fun publish(summary: WearSummary) {
        val request = PutDataRequest.create(WearSync.PATH_SUMMARY).apply {
            data = WearSync.encode(summary)
            // Weight is entered and read in the same minute on the watch, so the usual
            // batching window is too slow to be worth having.
            setUrgent()
        }
        // No watch paired, Play services disabled, or the person revoked it: none of those are
        // worth an error on the phone, because nothing on the phone depends on the result.
        runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
    }
}
