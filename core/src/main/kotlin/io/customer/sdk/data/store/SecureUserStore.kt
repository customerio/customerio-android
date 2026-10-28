package io.customer.sdk.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.util.Logger
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Encrypted, user-scoped preference store for sensitive data that must
 * persist across app restarts and be accessible without full SDK init.
 *
 * Unlike [GlobalPreferenceStore] (device-scoped, survives reset), this
 * store is cleared on clearIdentify/reset — all data is tied to the
 * current user session.
 *
 * All values are encrypted at rest via [PreferenceCrypto] (AES-256-GCM).
 * Currently stores user identity for direct API calls from WorkManager
 * workers and BroadcastReceivers (e.g., geofence events, push metrics).
 */
@InternalCustomerIOApi
interface SecureUserStore {
    fun saveUserId(userId: String?)
    fun getUserId(): String?
    fun clearAll()
}

internal class SecureUserStoreImpl(
    context: Context,
    private val logger: Logger,
    private val writeExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "cio-secure-user-store") },
    private val crypto: PreferenceCrypto = PreferenceCrypto(KEY_ALIAS, logger)
) : PreferenceStore(context), SecureUserStore {

    // Latest write in this process; null until the first one. Readers see a write before its
    // encrypted copy reaches disk, and a queued encrypt lands only while its write is still this one.
    private val current = AtomicReference<StoredUserId?>(null)
    private val writeLock = Any()

    override val prefsName: String by lazy {
        "io.customer.sdk.secure_user.${context.packageName}"
    }

    // Writes come from the host's thread (init, identify) and Keystore calls can block for seconds,
    // so encrypting runs on writeExecutor.
    override fun saveUserId(userId: String?) {
        if (userId == null) return clearUserId { remove(KEY_USER_ID) }
        val write = StoredUserId(userId)
        synchronized(writeLock) {
            // Switching users: drop the old id from disk now, so a kill before the encrypt lands
            // leaves no user rather than the previous one.
            val previous = current.get()
            if (previous != null && previous.userId != userId) prefs.edit { remove(KEY_USER_ID) }
            current.set(write)
        }
        writeExecutor.execute { persist(write, userId) }
    }

    override fun getUserId(): String? {
        current.get()?.let { return it.userId }
        return prefs.read {
            getString(KEY_USER_ID, null)?.let { crypto.decrypt(it) }
        }
    }

    override fun clearAll() = clearUserId { clear() }

    // No Keystore call, so this stays on the caller's thread and applies right away.
    private fun clearUserId(edit: SharedPreferences.Editor.() -> Unit) = synchronized(writeLock) {
        current.set(StoredUserId(null))
        prefs.edit(action = edit)
    }

    private fun persist(write: StoredUserId, userId: String) {
        try {
            if (current.get() !== write) return
            val encrypted = crypto.encrypt(userId)
            synchronized(writeLock) {
                if (current.get() === write) prefs.edit { putString(KEY_USER_ID, encrypted) }
            }
        } catch (e: Exception) {
            logger.error("Failed to persist secure user store: ${e.message}")
        }
    }

    private class StoredUserId(val userId: String?)

    companion object {
        private const val KEY_ALIAS = "cio_secure_user_key"
        private const val KEY_USER_ID = "cio_secure_user_id"
    }
}
