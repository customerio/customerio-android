package io.customer.location.geofence.store

import android.content.Context
import androidx.core.content.edit
import io.customer.location.geofence.GeofenceConfig
import io.customer.location.geofence.GeofenceJsonSerializer
import io.customer.location.geofence.GeofenceLocation
import io.customer.location.geofence.GeofenceRegion
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * State for the geofence sync pipeline:
 *
 *   cachedRegions               — full backend response; source for tier-A re-rank.
 *   registeredIds               — subset live in OS; drives the stale-cleanup diff.
 *   cachedConfig                — last server-driven thresholds.
 *   lastApiFetchLocation        — anchor for the tier-B distance check (rarely updated).
 *   lastMovementTriggerLocation — user's location at the most recent movement-trigger
 *                                  registration; used by boot restore to re-center
 *                                  closer to the user's real position than the anchor.
 *   lastSyncTimestamp           — freshness throttle for identify / app-launch.
 *
 * Decoding is schema-drift safe via [GeofenceJsonSerializer]: parse failures wipe the
 * key and return null/empty rather than propagating an exception up the sync path.
 */
internal interface GeofenceRegionStore {
    fun saveCachedRegions(regions: List<GeofenceRegion>)
    fun getCachedRegions(): List<GeofenceRegion>

    fun saveRegisteredIds(ids: Set<String>)
    fun getRegisteredIds(): Set<String>

    fun saveCachedConfig(config: GeofenceConfig)
    fun getCachedConfig(): GeofenceConfig?

    fun saveLastApiFetchLocation(location: GeofenceLocation)
    fun getLastApiFetchLocation(): GeofenceLocation?

    fun saveLastMovementTriggerLocation(location: GeofenceLocation)
    fun getLastMovementTriggerLocation(): GeofenceLocation?
    fun clearLastMovementTriggerLocation()

    fun getLastSyncTimestamp(): Long?
    fun setLastSyncTimestamp(timestamp: Long)

    fun clearAll()
}

internal class GeofenceRegionStoreImpl(
    context: Context,
    private val jsonSerializer: GeofenceJsonSerializer
) : PreferenceStore(context), GeofenceRegionStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.geofence_regions.${context.packageName}"
    }

    override fun saveCachedRegions(regions: List<GeofenceRegion>) =
        writeJson(KEY_CACHED_REGIONS, REGIONS_SERIALIZER, regions)

    override fun getCachedRegions(): List<GeofenceRegion> =
        readJson(KEY_CACHED_REGIONS, REGIONS_SERIALIZER) ?: emptyList()

    override fun saveRegisteredIds(ids: Set<String>) =
        writeJson(KEY_REGISTERED_IDS, ID_SET_SERIALIZER, ids)

    override fun getRegisteredIds(): Set<String> =
        readJson(KEY_REGISTERED_IDS, ID_SET_SERIALIZER) ?: emptySet()

    override fun saveCachedConfig(config: GeofenceConfig) =
        writeJson(KEY_CACHED_CONFIG, GeofenceConfig.serializer(), config)

    override fun getCachedConfig(): GeofenceConfig? =
        readJson(KEY_CACHED_CONFIG, GeofenceConfig.serializer())

    override fun saveLastApiFetchLocation(location: GeofenceLocation) =
        writeJson(KEY_LAST_API_FETCH_LOCATION, GeofenceLocation.serializer(), location)

    override fun getLastApiFetchLocation(): GeofenceLocation? =
        readJson(KEY_LAST_API_FETCH_LOCATION, GeofenceLocation.serializer())

    override fun saveLastMovementTriggerLocation(location: GeofenceLocation) =
        writeJson(KEY_LAST_MOVEMENT_TRIGGER_LOCATION, GeofenceLocation.serializer(), location)

    override fun getLastMovementTriggerLocation(): GeofenceLocation? =
        readJson(KEY_LAST_MOVEMENT_TRIGGER_LOCATION, GeofenceLocation.serializer())

    override fun clearLastMovementTriggerLocation() {
        prefs.edit { remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION) }
    }

    override fun getLastSyncTimestamp(): Long? = prefs.read {
        if (contains(KEY_LAST_SYNC)) getLong(KEY_LAST_SYNC, 0L) else null
    }

    override fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, timestamp) }
    }

    override fun clearAll() {
        prefs.edit { clear() }
    }

    private fun <T> writeJson(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit { putString(key, jsonSerializer.encode(serializer, value)) }
    }

    /**
     * Returns the decoded value, or `null` if the key is absent OR the stored
     * payload can't be parsed (schema drift, corruption). On parse failure the
     * key is wiped so a stale, unparseable value won't keep failing on every read.
     */
    private fun <T> readJson(key: String, serializer: KSerializer<T>): T? = prefs.read {
        val raw = getString(key, null) ?: return@read null
        jsonSerializer.decodeOrNull(serializer, raw) ?: run {
            prefs.edit { remove(key) }
            null
        }
    }

    private companion object {
        const val KEY_CACHED_REGIONS = "cached_regions"
        const val KEY_REGISTERED_IDS = "registered_ids"
        const val KEY_CACHED_CONFIG = "cached_config"
        const val KEY_LAST_API_FETCH_LOCATION = "last_api_fetch_location"
        const val KEY_LAST_MOVEMENT_TRIGGER_LOCATION = "last_movement_trigger_location"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        val REGIONS_SERIALIZER = ListSerializer(GeofenceRegion.serializer())
        val ID_SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
