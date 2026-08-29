package com.weighttrack.wear

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.weighttrack.core.sync.WearSummary
import com.weighttrack.core.sync.WearSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wearSummaryStore: DataStore<Preferences> by preferencesDataStore("wear-summary")

/**
 * The last figures the phone sent, kept on the watch.
 *
 * A tile and a complication are drawn when the system asks, not when the phone is in range, so
 * the watch has to be able to answer from its own storage. The payload is stored as it arrived
 * rather than field by field, so a phone on a newer version cannot half-write this.
 */
class WearSummaryStore(private val context: Context) {

    val summary: Flow<WearSummary?> = context.wearSummaryStore.data.map { preferences ->
        WearSync.decodeSummary(preferences[PAYLOAD]?.encodeToByteArray())
    }

    suspend fun save(payload: ByteArray) {
        // An undecodable payload is not written: a tile drawing the last good figures beats one
        // drawing nothing because a newer phone sent something this build cannot read.
        WearSync.decodeSummary(payload) ?: return
        context.wearSummaryStore.edit { it[PAYLOAD] = payload.decodeToString() }
    }

    private companion object {
        val PAYLOAD = stringPreferencesKey("summary")
    }
}
