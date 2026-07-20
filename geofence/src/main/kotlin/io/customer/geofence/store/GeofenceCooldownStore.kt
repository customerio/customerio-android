package io.customer.geofence.store

import android.content.Context
import androidx.core.content.edit
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read

/** Persists last-emitted timestamps for geofence event cooldown. */
internal interface GeofenceCooldownStore {
    fun getLastEmitTimestamp(geofenceId: String, transition: Event.GeofenceTransition): Long?
    fun recordEmit(geofenceId: String, transition: Event.GeofenceTransition, timestamp: Long)
    fun remove(geofenceId: String, transition: Event.GeofenceTransition)
    fun pruneOlderThan(cutoffTimestamp: Long)
    fun clearAll()
}

internal class GeofenceCooldownStoreImpl(
    context: Context
) : PreferenceStore(context), GeofenceCooldownStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.geofence_cooldown.${context.packageName}"
    }

    override fun getLastEmitTimestamp(
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): Long? = prefs.read {
        val key = cooldownKey(geofenceId, transition)
        if (contains(key)) getLong(key, 0L) else null
    }

    override fun recordEmit(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        timestamp: Long
    ) {
        prefs.edit { putLong(cooldownKey(geofenceId, transition), timestamp) }
    }

    override fun remove(geofenceId: String, transition: Event.GeofenceTransition) {
        prefs.edit { remove(cooldownKey(geofenceId, transition)) }
    }

    override fun pruneOlderThan(cutoffTimestamp: Long) {
        // MAX_VALUE default keeps entries whose value can't be read (never prune on doubt).
        val stale = prefs.read {
            all.keys.filter { key ->
                key.startsWith(KEY_PREFIX) && getLong(key, Long.MAX_VALUE) < cutoffTimestamp
            }
        }.orEmpty()
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach { remove(it) } }
    }

    override fun clearAll() {
        prefs.edit { clear() }
    }

    private fun cooldownKey(geofenceId: String, transition: Event.GeofenceTransition): String {
        return "$KEY_PREFIX${geofenceId}_${transition.name}"
    }

    internal companion object {
        private const val KEY_PREFIX = "cio_cooldown_"
    }
}
