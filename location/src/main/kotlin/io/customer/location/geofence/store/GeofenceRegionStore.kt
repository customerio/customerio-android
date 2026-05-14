package io.customer.location.geofence.store

import android.content.Context
import androidx.core.content.edit
import io.customer.location.geofence.GeofenceRegion
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists the set of geofence regions registered with the OS so they can be restored after process death. */
internal interface GeofenceRegionStore {
    fun saveAll(regions: List<GeofenceRegion>)
    fun getAll(): List<GeofenceRegion>
    fun clearAll()
    fun getLastSyncTimestamp(): Long?
    fun setLastSyncTimestamp(timestamp: Long)
}

internal class GeofenceRegionStoreImpl(
    context: Context
) : PreferenceStore(context), GeofenceRegionStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.geofence_regions.${context.packageName}"
    }

    override fun saveAll(regions: List<GeofenceRegion>) {
        prefs.edit {
            putString(KEY_REGIONS, Json.encodeToString(REGIONS_SERIALIZER, regions))
        }
    }

    override fun getAll(): List<GeofenceRegion> = prefs.read {
        val json = getString(KEY_REGIONS, null) ?: return@read emptyList()
        Json.decodeFromString(REGIONS_SERIALIZER, json)
    } ?: emptyList()

    override fun clearAll() {
        prefs.edit { clear() }
    }

    override fun getLastSyncTimestamp(): Long? = prefs.read {
        if (contains(KEY_LAST_SYNC)) getLong(KEY_LAST_SYNC, 0L) else null
    }

    override fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, timestamp) }
    }

    private companion object {
        const val KEY_REGIONS = "regions"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        val REGIONS_SERIALIZER = ListSerializer(GeofenceRegion.serializer())
    }
}
