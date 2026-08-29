package com.weighttrack.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.weighttrack.core.sync.WearSync
import com.weighttrack.core.sync.WearWeightLog
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.UUID

/** What happened to a reading entered on the watch. */
enum class LogResult {
    /** Handed to the Data Layer. It reaches the phone now, or the next time they are in range. */
    QUEUED,

    /** The phone half is not installed or the Data Layer refused it. */
    FAILED,
}

/**
 * Everything the watch says to the phone.
 *
 * Readings go out as data items rather than messages: the Data Layer holds an item until the
 * other side has it, so a weight entered on a walk with the phone at home still arrives. The
 * summary request is a message, because if it fails there is nothing to keep, only the cached
 * figures to carry on showing.
 */
class PhoneLink(private val context: Context) {

    suspend fun logWeight(
        grams: Int,
        at: Instant = Instant.now(),
        clientRecordId: String = UUID.randomUUID().toString(),
    ): LogResult {
        if (grams <= 0) return LogResult.FAILED
        val log = WearWeightLog(
            grams = grams,
            timestampUtcMillis = at.toEpochMilli(),
            clientRecordId = clientRecordId,
        )
        val request = PutDataRequest.create(WearSync.logPath(clientRecordId)).apply {
            data = WearSync.encode(log)
            setUrgent()
        }
        return runCatching { Wearable.getDataClient(context).putDataItem(request).await() }
            .fold(onSuccess = { LogResult.QUEUED }, onFailure = { LogResult.FAILED })
    }

    /** Nudges the phone to publish fresh figures. Quiet when it is out of range. */
    suspend fun requestSummary() {
        val nodes = runCatching {
            Wearable.getCapabilityClient(context)
                .getCapability(WearSync.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        }.getOrNull().orEmpty()

        nodes.forEach { node ->
            runCatching {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, WearSync.PATH_REQUEST_SUMMARY, ByteArray(0))
                    .await()
            }
        }
    }

    /** Whether a phone running WeightTrack is reachable right now. */
    suspend fun isPhoneReachable(): Boolean = runCatching {
        Wearable.getCapabilityClient(context)
            .getCapability(WearSync.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
            .isNotEmpty()
    }.getOrDefault(false)
}
