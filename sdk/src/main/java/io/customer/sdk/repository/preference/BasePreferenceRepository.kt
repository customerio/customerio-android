package io.customer.sdk.repository.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * Base class that can be shared among [CustomerIOComponent] and [CustomerIOSharedComponent] preference repositories
 */
internal abstract class BasePreferenceRepository(val context: Context) {

    abstract val prefsName: String

    internal val prefs: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

    // this would clear the prefs asynchronously
    open fun clearAll() {
        prefs.edit().clear().apply()
    }
}
