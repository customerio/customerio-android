package io.customer.sdk.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    /**
     * Persists [userId] (null removes it), then calls [onPersisted], so anything notified from
     * [onPersisted] finds the value through [getUserId]. Encrypts on the caller's thread, unless
     * the same userId is already stored or being stored: then no Keystore call is made, and
     * [onPersisted] runs now or once that write lands. A later save or [clearAll] that supersedes
     * this one drops both the write and [onPersisted].
     */
    fun saveUserId(userId: String?, onPersisted: () -> Unit = {})

    /**
     * Same as [saveUserId], but encrypts on a background thread so the caller never waits on the
     * Keystore. Until the write lands, [getUserId] returns the previously stored value.
     */
    fun saveUserIdInBackground(userId: String?, onPersisted: () -> Unit)

    fun getUserId(): String?
    fun clearAll()
}

internal class SecureUserStoreImpl(
    context: Context,
    logger: Logger,
    backgroundDispatcher: CoroutineDispatcher,
    private val crypto: PreferenceCrypto = PreferenceCrypto(KEY_ALIAS, logger)
) : PreferenceStore(context), SecureUserStore {

    private val writeScope = CoroutineScope(
        backgroundDispatcher + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            logger.error("Secure user store write failed: $throwable", throwable = throwable)
        }
    )

    // Orders saves and clears without holding a lock across a Keystore call: each one that changes
    // the stored user bumps [version], and an encrypted write lands only if its version is current.
    private val lock = Any()
    private var version = 0L

    // Written by this process, so saving the same user again needs no encryption.
    private var persistedUserId: String? = null
    private var pendingWrite: PendingWrite? = null

    private class PendingWrite(val userId: String, val version: Long) {
        val onPersisted = mutableListOf<() -> Unit>()
    }

    override val prefsName: String by lazy {
        "io.customer.sdk.secure_user.${context.packageName}"
    }

    override fun saveUserId(userId: String?, onPersisted: () -> Unit) {
        val write = startWrite(userId, onPersisted) ?: return
        finishWrite(write, crypto.encrypt(write.userId))
    }

    override fun saveUserIdInBackground(userId: String?, onPersisted: () -> Unit) {
        val write = startWrite(userId, onPersisted) ?: return
        writeScope.launch {
            // Superseded before it started: skip the Keystore call rather than hold the key ahead of the newer save.
            if (synchronized(lock) { write.version != version }) return@launch
            finishWrite(write, crypto.encrypt(write.userId))
        }
    }

    /** Returns the write to encrypt, or null when [userId] needs no Keystore call. */
    private fun startWrite(userId: String?, onPersisted: () -> Unit): PendingWrite? = synchronized(lock) {
        val pending = pendingWrite
        when {
            userId == null -> {
                resetLocked { remove(KEY_USER_ID) }
                onPersisted()
                null
            }

            pending?.userId == userId -> {
                pending.onPersisted += onPersisted
                null
            }

            pending == null && persistedUserId == userId -> {
                onPersisted()
                null
            }

            else -> PendingWrite(userId, ++version).also {
                it.onPersisted += onPersisted
                pendingWrite = it
            }
        }
    }

    // Callbacks run under the lock, so a clear can't slip between this write and its event.
    private fun finishWrite(write: PendingWrite, encrypted: String) = synchronized(lock) {
        if (write.version != version) return@synchronized
        pendingWrite = null
        prefs.edit { putString(KEY_USER_ID, encrypted) }
        persistedUserId = write.userId
        write.onPersisted.forEach { it() }
    }

    override fun getUserId(): String? = prefs.read {
        getString(KEY_USER_ID, null)?.let { crypto.decrypt(it) }
    }

    override fun clearAll() = synchronized(lock) {
        resetLocked { clear() }
    }

    private fun resetLocked(edit: SharedPreferences.Editor.() -> Unit) {
        version++
        pendingWrite = null
        persistedUserId = null
        prefs.edit(action = edit)
    }

    companion object {
        private const val KEY_ALIAS = "cio_secure_user_key"
        private const val KEY_USER_ID = "cio_secure_user_id"
    }
}
