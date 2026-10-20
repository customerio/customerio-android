package io.customer.sdk.data.store

import android.content.Context
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
    // Keystore calls can block for seconds, and writes come from the host's thread (init, identify).
    private val writeExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "cio-secure-user-store") }
) : PreferenceStore(context), SecureUserStore {

    private val crypto = PreferenceCrypto(KEY_ALIAS, logger)

    // Latest value this process wrote; null until the first write. Readers see a write before
    // its encrypted copy reaches disk.
    private val current = AtomicReference<StoredUserId?>(null)
    private val writeLock = Any()

    override val prefsName: String by lazy {
        "io.customer.sdk.secure_user.${context.packageName}"
    }

    override fun saveUserId(userId: String?) = enqueueWrite(userId) {
        prefs.edit(commit = true) {
            if (userId != null) {
                putString(KEY_USER_ID, crypto.encrypt(userId))
            } else {
                remove(KEY_USER_ID)
            }
        }
    }

    override fun getUserId(): String? {
        current.get()?.let { return it.userId }
        return prefs.read {
            getString(KEY_USER_ID, null)?.let { crypto.decrypt(it) }
        }
    }

    override fun clearAll() = enqueueWrite(null) {
        prefs.edit(commit = true) { clear() }
    }

    // One lock so the queue order always matches the in-memory order.
    private fun enqueueWrite(userId: String?, persist: () -> Unit) = synchronized(writeLock) {
        current.set(StoredUserId(userId))
        writeExecutor.execute {
            try {
                persist()
            } catch (e: Exception) {
                logger.error("Failed to persist secure user store: ${e.message}")
            }
        }
    }

    private class StoredUserId(val userId: String?)

    companion object {
        private const val KEY_ALIAS = "cio_secure_user_key"
        private const val KEY_USER_ID = "cio_secure_user_id"
    }
}
