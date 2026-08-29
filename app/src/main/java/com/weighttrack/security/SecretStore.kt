package com.weighttrack.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turning a secret into something safe to write down, and back again.
 *
 * Separate from [SecretStore] because the key comes from the Android keystore, which does not
 * exist off a device, and the part worth testing is everything else: the marker that keeps an
 * older plain value readable, the nonce that stops two identical passwords looking identical, and
 * what happens when the stored text is rubbish.
 */
internal object Secrets {

    /** Says the value is encrypted, so a value written before this existed is still readable. */
    const val PREFIX = "enc1:"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun protect(secret: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        // The nonce goes with the text. It is not a secret, and each encryption needs its own.
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /**
     * Reads one back.
     *
     * Anything without the marker is returned unchanged, which is how a password stored before
     * this existed keeps working rather than sync stopping with no explanation.
     */
    fun reveal(stored: String, key: SecretKey): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES),
            )
            cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun isProtected(stored: String): Boolean = stored.startsWith(PREFIX)
}

/**
 * Keeps a password out of a file that can be read as text.
 *
 * The WebDAV password and the USDA key sat in DataStore in plain sight. That file is in internal
 * storage and excluded from backup and device transfer, so nothing was walking out of the front
 * door, but a credential to somebody's own server should not be legible to anything that ever
 * gets at the file.
 *
 * The key lives in the Android keystore and never leaves it, so what is written down means
 * nothing anywhere else. That is deliberate: an export carrying a working password would be a
 * worse thing than an export missing one.
 */
@Singleton
class SecretStore internal constructor(keySource: () -> SecretKey) {

    /**
     * Fetched once and held.
     *
     * Settings are a flow, and its mapping runs in whoever is collecting, which for several view
     * models is the interface thread. Looking the key up per read meant a binder call into
     * keystore2 on every preference change, for anybody who had ever stored a password.
     */
    private val key: SecretKey by lazy(keySource)

    /**
     * The real one, backed by the Android keystore.
     *
     * The other constructor exists so a test can supply a key of the same shape: the keystore
     * does not exist off a device, and without a seam every test would only ever exercise the
     * fallback that stores the value plain.
     */
    @Inject
    constructor() : this({ keystoreKey() })

    /**
     * Answers null when the phone will not give a key, which no ordinary device does.
     *
     * A caller that gets null stores the plain value, because somebody whose sync quietly stopped
     * working is worse off than somebody whose password sits in a file only root can read.
     */
    fun protect(secret: String): String? =
        runCatching { Secrets.protect(secret, key) }.getOrNull()

    /**
     * Reads one back.
     *
     * A value with no marker is handed back without touching the keystore at all, which is what
     * a phone that has never stored a password does on every single preference read.
     */
    fun reveal(stored: String): String? {
        if (!Secrets.isProtected(stored)) return stored
        return runCatching { Secrets.reveal(stored, key) }.getOrNull()
    }

    fun isProtected(stored: String): Boolean = Secrets.isProtected(stored)

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "weighttrack-secrets"

        fun keystoreKey(): SecretKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
