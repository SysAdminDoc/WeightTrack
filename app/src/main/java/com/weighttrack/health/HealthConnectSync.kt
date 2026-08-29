package com.weighttrack.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.weighttrack.core.model.EntrySource
import com.weighttrack.core.model.WeightEntry
import com.weighttrack.data.prefs.SettingsRepository
import com.weighttrack.data.repo.WeightRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class HealthConnectAvailability {
    INSTALLED,
    UPDATE_REQUIRED,
    NOT_SUPPORTED,
}

data class HealthConnectSyncResult(
    val imported: Int,
    val exported: Int,
    val skipped: Int,
)

/**
 * Two-way sync with Health Connect.
 *
 * This is how a Withings, Renpho or Samsung scale reaches the app without WeightTrack ever
 * touching Bluetooth. The hard part is not reading records, it is not creating duplicates:
 * every record written carries a client record id, and every record read is matched against
 * both that and the Health Connect id before anything is inserted.
 */
@Singleton
class HealthConnectSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val weightRepository: WeightRepository,
    private val settingsRepository: SettingsRepository,
) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
    )

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.INSTALLED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.NOT_SUPPORTED
        }

    private fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthConnectAvailability.INSTALLED) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(): Boolean {
        val client = clientOrNull() ?: return false
        return runCatching {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    /**
     * Pulls new readings in, then pushes ours out.
     *
     * The read window reaches back years rather than days because the whole point for a new
     * user is to inherit the history their scale app already holds. Reading beyond thirty days
     * needs the history permission; without it Health Connect simply returns less, which is
     * why this does not fail when the grant is missing.
     */
    suspend fun sync(sinceDays: Long = 365 * 5): Result<HealthConnectSyncResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = clientOrNull() ?: error("Health Connect is not available on this device.")
                if (!hasPermissions()) error("WeightTrack has not been granted Health Connect access.")

                val zone = ZoneId.systemDefault()
                val start = Instant.now().minus(sinceDays, ChronoUnit.DAYS)
                val imported = importWeights(client, start, zone)
                val exported = exportWeights(client, zone)
                HealthConnectSyncResult(
                    imported = imported.first,
                    exported = exported,
                    skipped = imported.second,
                )
            }
        }

    private suspend fun importWeights(
        client: HealthConnectClient,
        start: Instant,
        zone: ZoneId,
    ): Pair<Int, Int> {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, Instant.now()),
            ),
        )
        var imported = 0
        var skipped = 0
        response.records.forEach { record ->
            val grams = (record.weight.inKilograms * 1000).toInt()
            if (grams <= 0) {
                skipped++
                return@forEach
            }
            // A record we wrote comes back carrying our own client id. Re-importing it would
            // be harmless thanks to the upsert, but skipping keeps the counts honest.
            val ourClientId = record.metadata.clientRecordId
            if (ourClientId != null && weightRepository.byClientRecordId(ourClientId) != null) {
                skipped++
                return@forEach
            }
            weightRepository.add(
                grams = grams,
                timestamp = record.time,
                zone = zone,
                source = EntrySource.HEALTH_CONNECT,
                healthConnectId = record.metadata.id,
                clientRecordId = ourClientId ?: "hc:${record.metadata.id}",
            )
            imported++
        }
        return imported to skipped
    }

    private suspend fun exportWeights(client: HealthConnectClient, zone: ZoneId): Int {
        val settings = settingsRepository.settings.first()
        val entries = weightRepository.observeEntries().first()
            .filter { it.source != EntrySource.HEALTH_CONNECT }
        if (entries.isEmpty()) return 0

        val records = entries.map { entry -> entry.toWeightRecord(zone) }
        // Insert is an upsert when the client record id matches, so pushing the same log twice
        // updates rather than duplicates. Batched because a first sync can be thousands of rows.
        var written = 0
        records.chunked(BATCH_SIZE).forEach { batch ->
            runCatching { client.insertRecords(batch) }
                .onSuccess { written += batch.size }
        }
        // Height is written once, not per reading, so it never floods the other app's log.
        settings.profile.heightMm.takeIf { it > 0 }?.let { heightMm ->
            runCatching {
                client.insertRecords(
                    listOf(
                        HeightRecord(
                            time = Instant.now(),
                            zoneOffset = zone.rules.getOffset(Instant.now()),
                            height = androidx.health.connect.client.units.Length.meters(heightMm / 1000.0),
                            metadata = Metadata.manualEntry(device = Device(type = Device.TYPE_PHONE)),
                        ),
                    ),
                )
            }
        }
        return written
    }

    private fun WeightEntry.toWeightRecord(zone: ZoneId): WeightRecord = WeightRecord(
        time = timestamp,
        zoneOffset = zone.rules.getOffset(timestamp),
        weight = Mass.kilograms(grams / 1000.0),
        metadata = Metadata.manualEntry(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = clientRecordId,
        ),
    )

    /** Body fat is written separately because Health Connect models it as its own record. */
    suspend fun exportBodyFat(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val client = clientOrNull() ?: return@runCatching 0
            val zone = ZoneId.systemDefault()
            val records = weightRepository.observeEntries().first()
                .filter { it.bodyFatPercent != null && it.source != EntrySource.HEALTH_CONNECT }
                .map { entry ->
                    BodyFatRecord(
                        time = entry.timestamp,
                        zoneOffset = zone.rules.getOffset(entry.timestamp),
                        percentage = Percentage(entry.bodyFatPercent!!),
                        metadata = Metadata.manualEntry(
                            device = Device(type = Device.TYPE_PHONE),
                            clientRecordId = "bf:${entry.clientRecordId}",
                        ),
                    )
                }
            if (records.isEmpty()) return@runCatching 0
            records.chunked(BATCH_SIZE).forEach { client.insertRecords(it) }
            records.size
        }
    }

    private companion object {
        const val BATCH_SIZE = 200
    }
}
