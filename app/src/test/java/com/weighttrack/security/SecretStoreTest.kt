package com.weighttrack.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Keeping a password out of a file that can be read as text.
 *
 * The WebDAV password and the USDA key sat in DataStore in plain sight. The file is excluded from
 * backup and device transfer and sits in internal storage, so nothing was walking out of the front
 * door, but a credential to somebody's own server should not be legible to anything that ever gets
 * at the file.
 */
@RunWith(RobolectricTestRunner::class)
class SecretStoreTest {

    /**
     * A key of the same shape the keystore gives, generated here.
     *
     * The Android keystore does not exist off a device, so a test against [SecretStore] itself
     * would only ever exercise its fallback. The part worth covering is the rest.
     */
    private val key: javax.crypto.SecretKey =
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val store = object {
        fun protect(secret: String): String? = Secrets.protect(secret, key)
        fun reveal(stored: String): String? = Secrets.reveal(stored, key)
        fun isProtected(stored: String): Boolean = Secrets.isProtected(stored)
    }

    @Test
    fun `a protected secret does not contain the secret`() {
        val protected = store.protect("hunter2-and-a-long-tail")

        assertThat(protected).isNotNull()
        assertThat(protected).doesNotContain("hunter2")
        assertThat(protected).doesNotContain("and-a-long-tail")
    }

    @Test
    fun `what goes in comes back out`() {
        val secret = "correct horse battery staple"

        val back = store.reveal(store.protect(secret)!!)

        assertThat(back).isEqualTo(secret)
    }

    @Test
    fun `a value written before this existed still reads`() {
        // Anything without the marker is handed back as it is, so an upgrade does not lose
        // somebody's password and stop their sync with no explanation.
        assertThat(store.reveal("plain-old-password")).isEqualTo("plain-old-password")
        assertThat(store.isProtected("plain-old-password")).isFalse()
    }

    @Test
    fun `the same secret twice does not produce the same text`() {
        // Each encryption gets its own nonce. Without that, two people with the same password
        // would have the same stored value, which says something the file should not say.
        val first = store.protect("same")
        val second = store.protect("same")

        assertThat(first).isNotEqualTo(second)
        assertThat(store.reveal(first!!)).isEqualTo("same")
        assertThat(store.reveal(second!!)).isEqualTo("same")
    }

    @Test
    fun `a protected value is marked as one`() {
        assertThat(store.isProtected(store.protect("x")!!)).isTrue()
    }

    @Test
    fun `an empty secret round trips`() {
        assertThat(store.reveal(store.protect("")!!)).isEmpty()
    }

    @Test
    fun `a secret with awkward characters round trips`() {
        val awkward = "pa$£€ word \"with\" 'quotes' and äöü"

        assertThat(store.reveal(store.protect(awkward)!!)).isEqualTo(awkward)
    }

    @Test
    fun `the stored preferences hold no readable password`() = kotlinx.coroutines.test.runTest {
        // What the item is actually about: not the cipher, but what ends up in the file.
        val keyed = SecretStore { key }
        val preferences = com.weighttrack.data.InMemoryPreferences()
        val sync = com.weighttrack.data.sync.SyncPreferences(preferences, keyed)

        sync.useWebDav("https://cloud.example.com/dav", "me", "hunter2-and-a-long-tail")

        val written = preferences.data.value.asMap().values.joinToString(" ")
        assertThat(written).doesNotContain("hunter2")
        // And it still comes back for the sync that needs it.
        assertThat(sync.current().webDavPassword).isEqualTo("hunter2-and-a-long-tail")
    }

    @Test
    fun `a password stored before this existed still works`() = kotlinx.coroutines.test.runTest {
        val keyed = SecretStore { key }
        val preferences = com.weighttrack.data.InMemoryPreferences()
        preferences.updateData {
            it.toMutablePreferences().apply {
                set(androidx.datastore.preferences.core.stringPreferencesKey("sync_webdav_password"), "old-plain")
            }
        }

        val sync = com.weighttrack.data.sync.SyncPreferences(preferences, keyed)

        assertThat(sync.current().webDavPassword).isEqualTo("old-plain")
    }

    @Test
    fun `rubbish that claims to be protected does not throw`() {
        // A truncated or corrupted file should not take the app down; the caller sees no
        // password and is asked for it again.
        assertThat(store.reveal("enc1:not-base-64-at-all")).isNull()
        assertThat(store.reveal("enc1:")).isNull()
    }
}
