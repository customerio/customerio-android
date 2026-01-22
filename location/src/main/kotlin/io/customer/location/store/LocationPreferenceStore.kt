package io.customer.location.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/**
 * Preference store for location module settings.
 *
 * Extends the SDK's PreferenceStore pattern for consistent storage handling.
 */
internal class LocationPreferenceStore(
    context: Context
) : PreferenceStore(context) {

    override val prefsName: String = "io.customer.location.${context.packageName}"

    /**
     * Whether location tracking is enabled by the app.
     * This is the app-level opt-in, separate from OS permissions.
     */
    var isTrackingEnabled: Boolean
        get() = prefs.read { getBoolean(KEY_TRACKING_ENABLED, false) } ?: false
        set(value) = prefs.edit { putBoolean(KEY_TRACKING_ENABLED, value) }

    /**
     * Whether we've already requested location permission from the user.
     * Used to distinguish between "not determined" and "denied" states.
     */
    var hasRequestedPermission: Boolean
        get() = prefs.read { getBoolean(KEY_PERMISSION_REQUESTED, false) } ?: false
        set(value) = prefs.edit { putBoolean(KEY_PERMISSION_REQUESTED, value) }

    companion object {
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_PERMISSION_REQUESTED = "permission_requested"
    }
}
