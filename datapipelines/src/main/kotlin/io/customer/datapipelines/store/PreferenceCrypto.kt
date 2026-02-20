package io.customer.datapipelines.store

import android.annotation.SuppressLint
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.customer.sdk.core.util.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts strings using an AES-256-GCM key stored in the
 * Android Keystore. Falls back to plaintext on API < 23 or when the
 * Keystore is unavailable (some OEMs have buggy implementations).
 *
 * The [MODE_PRIVATE][android.content.Context.MODE_PRIVATE] SharedPreferences
 * sandbox remains the baseline protection in all cases.
 */
internal class PreferenceCrypto(
    private val keyAlias: String,
    private val logger: Logger
) {
    private val isKeystoreAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    @Volatile
    private var cachedKey: SecretKey? = null

    @SuppressLint("NewApi", "InlinedApi")
    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (entry != null) {
            cachedKey = entry.secretKey
            return entry.secretKey
        }

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            .apply { init(spec) }
            .generateKey()
        cachedKey = key
        return key
    }

    /**
     * Encrypts [plaintext] with AES-256-GCM. Returns a Base64 string
     * containing the 12-byte IV prepended to the ciphertext. Falls back
     * to returning [plaintext] unchanged if encryption is unavailable.
     */
    @SuppressLint("NewApi")
    fun encrypt(plaintext: String): String {
        if (!isKeystoreAvailable) return plaintext

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + ciphertext
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            logger.debug("Keystore encryption unavailable, storing without encryption: ${e.message}")
            plaintext
        }
    }

    /**
     * Decrypts an [encoded] Base64 string produced by [encrypt]. If
     * decryption fails (e.g. the value was stored as plaintext before
     * encryption was enabled, or the Keystore is unavailable), returns
     * [encoded] as-is, which handles migration from plaintext transparently.
     */
    @SuppressLint("NewApi")
    fun decrypt(encoded: String): String {
        if (!isKeystoreAvailable) return encoded

        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return encoded

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // Value is likely stored as plaintext from before encryption was
            // enabled, or from a Keystore failure during write. Return as-is.
            encoded
        }
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }
}
