package io.customer.sdk.data.store

import android.content.Context
import android.content.SharedPreferences

/**
 * Base preference repository that can be reused among different preference repositories.
 */
abstract class PreferenceStore(val context: Context) {
    abstract val prefsName: String

    protected val prefs: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

    /**
     * Clear all preferences stored in associated preference file asynchronously.
     */
    open fun clearAll() {
        prefs.edit().clear().apply()
    }
}

/**
 * Retrieves value from the preference file using the provided action.
 * This method handles any exceptions that might occur during the retrieval
 * process and returns null in case of an exception.
 */
inline fun <Value> SharedPreferences.read(
    action: SharedPreferences.() -> Value?
): Value? = runCatching { action(this) }.getOrNull()
