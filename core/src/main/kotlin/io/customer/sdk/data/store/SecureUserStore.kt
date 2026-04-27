package io.customer.sdk.data.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.core.util.Logger

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
interface SecureUserStore {
    fun saveUserId(userId: String?)
    fun getUserId(): String?
    fun clearAll()
}

internal class SecureUserStoreImpl(
    context: Context,
    logger: Logger
) : PreferenceStore(context), SecureUserStore {

    private val crypto = PreferenceCrypto(KEY_ALIAS, logger)

    override val prefsName: String by lazy {
        "io.customer.sdk.secure_user.${context.packageName}"
    }

    override fun saveUserId(userId: String?) = prefs.edit {
        if (userId != null) {
            putString(KEY_USER_ID, crypto.encrypt(userId))
        } else {
            remove(KEY_USER_ID)
        }
    }

    override fun getUserId(): String? = prefs.read {
        getString(KEY_USER_ID, null)?.let { crypto.decrypt(it) }
    }

    override fun clearAll() = prefs.edit { clear() }

    companion object {
        private const val KEY_ALIAS = "cio_secure_user_key"
        private const val KEY_USER_ID = "cio_secure_user_id"
    }
}
