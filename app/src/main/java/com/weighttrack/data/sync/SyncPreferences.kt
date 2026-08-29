package com.weighttrack.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where this phone puts its sync file, if anywhere. */
enum class SyncMode {
    /** Not syncing. The app works exactly as it always has. */
    OFF,

    /** A folder on the phone, kept in step with another device by something like Syncthing. */
    FOLDER,

    /** A directory on a WebDAV server, such as Nextcloud. */
    WEBDAV,
}

data class SyncSettings(
    val mode: SyncMode = SyncMode.OFF,
    /** The folder picked through the storage picker, as a tree address. */
    val folderUri: String? = null,
    val webDavUrl: String? = null,
    val webDavUser: String? = null,
    val webDavPassword: String? = null,
    /**
     * What this phone is called in the folder.
     *
     * Generated once and then never changed. A device that renamed itself would leave its old
     * file behind, and that file would go on offering its stale copy of everything forever.
     */
    val deviceId: String = "",
    val lastSyncAtUtcMillis: Long = 0,
    val lastSyncMessage: String? = null,
    val syncInBackground: Boolean = true,
) {
    val isOn: Boolean get() = mode != SyncMode.OFF

    /** Whether the chosen place has actually been set up. */
    val isReady: Boolean
        get() = when (mode) {
            SyncMode.OFF -> false
            SyncMode.FOLDER -> !folderUri.isNullOrBlank()
            SyncMode.WEBDAV -> !webDavUrl.isNullOrBlank() && !webDavUser.isNullOrBlank()
        }
}

@Singleton
class SyncPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<SyncSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): SyncSettings = settings.first()

    /**
     * This phone's name in the folder, made on first use.
     *
     * Not derived from anything about the device. A model name would collide between two
     * identical phones, and an advertising or hardware identifier is a thing to be avoided in an
     * app with no account and no analytics.
     */
    suspend fun deviceId(): String {
        val existing = current().deviceId
        if (existing.isNotBlank()) return existing
        val made = UUID.randomUUID().toString().replace("-", "").take(12)
        dataStore.edit { it[Keys.DEVICE_ID] = made }
        return made
    }

    suspend fun useFolder(uri: String) = dataStore.edit {
        it[Keys.MODE] = SyncMode.FOLDER.name
        it[Keys.FOLDER_URI] = uri
    }

    suspend fun useWebDav(url: String, user: String, password: String) = dataStore.edit {
        it[Keys.MODE] = SyncMode.WEBDAV.name
        it[Keys.WEBDAV_URL] = url.trim()
        it[Keys.WEBDAV_USER] = user.trim()
        it[Keys.WEBDAV_PASSWORD] = password
    }

    /**
     * Stops syncing, and forgets the password on the way out.
     *
     * The address and username are kept so turning it back on is not a retyping exercise. The
     * password is not, because somebody switching sync off is as likely to be handing the phone
     * on as pausing.
     */
    suspend fun turnOff() = dataStore.edit {
        it[Keys.MODE] = SyncMode.OFF.name
        it.remove(Keys.WEBDAV_PASSWORD)
    }

    suspend fun setBackground(enabled: Boolean) = dataStore.edit {
        it[Keys.BACKGROUND] = enabled
    }

    suspend fun recordSync(atUtcMillis: Long, message: String?) = dataStore.edit {
        it[Keys.LAST_SYNC_AT] = atUtcMillis
        if (message == null) it.remove(Keys.LAST_MESSAGE) else it[Keys.LAST_MESSAGE] = message
    }

    private fun Preferences.toSettings() = SyncSettings(
        mode = this[Keys.MODE]?.let { name ->
            runCatching { SyncMode.valueOf(name) }.getOrNull()
        } ?: SyncMode.OFF,
        folderUri = this[Keys.FOLDER_URI],
        webDavUrl = this[Keys.WEBDAV_URL],
        webDavUser = this[Keys.WEBDAV_USER],
        webDavPassword = this[Keys.WEBDAV_PASSWORD],
        deviceId = this[Keys.DEVICE_ID].orEmpty(),
        lastSyncAtUtcMillis = this[Keys.LAST_SYNC_AT] ?: 0,
        lastSyncMessage = this[Keys.LAST_MESSAGE],
        syncInBackground = this[Keys.BACKGROUND] ?: true,
    )

    private object Keys {
        val MODE = stringPreferencesKey("sync_mode")
        val FOLDER_URI = stringPreferencesKey("sync_folder_uri")
        val WEBDAV_URL = stringPreferencesKey("sync_webdav_url")
        val WEBDAV_USER = stringPreferencesKey("sync_webdav_user")
        val WEBDAV_PASSWORD = stringPreferencesKey("sync_webdav_password")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val LAST_SYNC_AT = longPreferencesKey("sync_last_at")
        val LAST_MESSAGE = stringPreferencesKey("sync_last_message")
        val BACKGROUND = booleanPreferencesKey("sync_background")
    }
}
