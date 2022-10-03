package io.customer.sdk.repository.preference

import android.content.Context
import android.content.SharedPreferences

internal abstract class BasePreferenceRepository(val context: Context) {

    abstract val prefsName: String

    internal val prefs: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(
            prefsName,
            Context.MODE_PRIVATE
        )

    open fun clearAll() {
        prefs.edit().clear().apply()
    }
}
