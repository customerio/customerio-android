package io.customer.geofence.store

import android.content.Context
import androidx.core.content.edit
import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceRegion
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.PreferenceCrypto
import io.customer.sdk.data.store.PreferenceStore
import io.customer.sdk.data.store.read
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * State for the geofence sync pipeline. Keys split by sign-out lifecycle
 * (see [clearUserScopedState]):
 *
 * Preserved across sign-out:
 *   cachedRegions — full backend response; source for tier-A re-rank.
 *   cachedConfig  — last server-driven thresholds.
 *
 * Cleared on sign-out:
 *   registeredIds               — subset live in OS; drives the stale-cleanup diff.
 *   enteredIds                  — fences the device is inside; goes with the registrations it
 *                                  describes, since sign-out drops those from the OS.
 *   lastApiFetchLocation        — anchor for the tier-B distance check (rarely updated).
 *   lastMovementTriggerLocation — user's location at the most recent movement-trigger
 *                                  registration; used by boot restore to re-center
 *                                  closer to the user's real position than the anchor.
 *   lastSyncTimestamp           — freshness throttle; cleared so the next login re-fetches.
 *
 * Rationale: the backend geofence fetch is workspace-scoped (no userId on
 * the wire), so cached regions/config stay valid for any user in the workspace
 * and are kept. Dropping the freshness timestamp makes the next login re-fetch
 * instead of riding the prior session's window. If backend ever adds per-user
 * filtering, revisit.
 *
 * Decoding is schema-drift safe via [GeofenceJsonSerializer]: parse failures
 * wipe the key and return null/empty rather than propagating an exception up
 * the sync path.
 *
 * Storage: SharedPreferences. Workspace configuration (geofence IDs, names,
 * lat/lng, radii, external IDs) is plaintext — UID isolation keeps it
 * app-private. The two user-location snapshots are encrypted at rest via
 * [PreferenceCrypto] (AES-256-GCM, Android Keystore) and cleared on sign-out.
 */
internal interface GeofenceRegionStore {
    fun saveCachedRegions(regions: List<GeofenceRegion>)
    fun getCachedRegions(): List<GeofenceRegion>

    /** The cached region with [id], or null if it isn't cached. */
    fun getCachedRegion(id: String): GeofenceRegion? = getCachedRegions().find { it.id == id }

    fun saveRegisteredIds(ids: Set<String>)
    fun getRegisteredIds(): Set<String>

    /** Fences the device is known to be inside. Drives the EXIT guard — see [claimExit]. */
    fun getEnteredIds(): Set<String>

    /** Records that the device is inside [geofenceId]. Idempotent. */
    fun recordEntered(geofenceId: String)

    /**
     * Atomically drops [geofenceId] from the entered set, returning whether it was there.
     *
     * `false` means we have no record of the device ever being inside, so the EXIT is a GMS
     * reconciliation artifact rather than a crossing: GMS can mark a fence INSIDE while evaluating
     * a registration against a coarse fix, emit no initial ENTER, then report EXIT once an accurate
     * fix arrives — observed on a fence the device was never within 500m of.
     */
    fun claimExit(geofenceId: String): Boolean

    /**
     * Prunes the entered set to [registeredIds] and unions in [inside], the fences our own geometry
     * puts the device within at registration time.
     *
     * Union rather than replace: [inside] may be computed from the persisted anchor rather than a
     * live fix, and a stale anchor that wrongly reports "outside" must not erase real containment —
     * that would swallow a genuine EXIT.
     */
    fun reconcileEnteredIds(registeredIds: Set<String>, inside: Set<String>)

    /**
     * Whether containment has ever been recorded, i.e. whether the entered set carries data at all.
     *
     * False on an install upgraded from an SDK version that predates the set, until the first
     * registration seeds it. The EXIT guard defers while this is false: an empty set and "no data
     * yet" are indistinguishable from [getEnteredIds] alone, and treating the second as the first
     * would drop a genuine EXIT for a fence entered before the upgrade.
     */
    fun hasContainmentRecord(): Boolean

    /** Device uptime at the last successful OS registration; null if never registered. Drives reboot detection. */
    fun getLastRegistrationUptime(): Long?
    fun setLastRegistrationUptime(uptimeMs: Long)

    /** Package lastUpdateTime at the last successful OS registration; null if never registered. Drives app-update detection. */
    fun getLastRegistrationPackageUpdateTime(): Long?
    fun setLastRegistrationPackageUpdateTime(timeMs: Long)

    fun saveCachedConfig(config: GeofenceConfig)
    fun getCachedConfig(): GeofenceConfig?

    fun saveLastApiFetchLocation(location: GeofenceLocation)
    fun getLastApiFetchLocation(): GeofenceLocation?

    fun saveLastMovementTriggerLocation(location: GeofenceLocation)
    fun getLastMovementTriggerLocation(): GeofenceLocation?
    fun clearLastMovementTriggerLocation()

    fun getLastSyncTimestamp(): Long?
    fun setLastSyncTimestamp(timestamp: Long)

    /**
     * Sign-out wipe. Drops the anchor, movement-trigger location, registered
     * IDs and the freshness timestamp (so the next login re-fetches); keeps
     * cached regions/config.
     */
    fun clearUserScopedState()

    fun clearAll()
}

/** Cached config, or the constant fallback when none is cached. */
internal fun GeofenceRegionStore.getCachedConfigOrFallback(): GeofenceConfig =
    getCachedConfig() ?: GeofenceConfig.fallback()

internal class GeofenceRegionStoreImpl(
    context: Context,
    private val jsonSerializer: GeofenceJsonSerializer,
    logger: Logger
) : PreferenceStore(context), GeofenceRegionStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.geofence_regions.${context.packageName}"
    }

    private val crypto = PreferenceCrypto(CRYPTO_KEY_ALIAS, logger)

    override fun saveCachedRegions(regions: List<GeofenceRegion>) =
        writeJson(KEY_CACHED_REGIONS, REGIONS_SERIALIZER, regions)

    override fun getCachedRegions(): List<GeofenceRegion> =
        readJson(KEY_CACHED_REGIONS, REGIONS_SERIALIZER) ?: emptyList()

    override fun saveRegisteredIds(ids: Set<String>) =
        writeJson(KEY_REGISTERED_IDS, ID_SET_SERIALIZER, ids)

    override fun getRegisteredIds(): Set<String> =
        readJson(KEY_REGISTERED_IDS, ID_SET_SERIALIZER) ?: emptySet()

    override fun getEnteredIds(): Set<String> =
        readJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER) ?: emptySet()

    override fun hasContainmentRecord(): Boolean = prefs.read { contains(KEY_ENTERED_IDS) } ?: false

    // The three mutators share a lock because each is a read-modify-write, and transitions arrive
    // on the receiver's coroutine while registration runs on the sync path.
    override fun recordEntered(geofenceId: String) = synchronized(enteredLock) {
        val current = getEnteredIds()
        if (geofenceId !in current) {
            writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, current + geofenceId)
        }
    }

    override fun claimExit(geofenceId: String): Boolean = synchronized(enteredLock) {
        val current = getEnteredIds()
        if (geofenceId !in current) return@synchronized false
        writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, current - geofenceId)
        true
    }

    override fun reconcileEnteredIds(registeredIds: Set<String>, inside: Set<String>) = synchronized(enteredLock) {
        writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, (getEnteredIds() intersect registeredIds) + inside)
    }

    override fun getLastRegistrationUptime(): Long? = prefs.read {
        if (contains(KEY_LAST_REGISTRATION_UPTIME)) getLong(KEY_LAST_REGISTRATION_UPTIME, 0L) else null
    }

    override fun setLastRegistrationUptime(uptimeMs: Long) {
        prefs.edit { putLong(KEY_LAST_REGISTRATION_UPTIME, uptimeMs) }
    }

    override fun getLastRegistrationPackageUpdateTime(): Long? = prefs.read {
        if (contains(KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME)) getLong(KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME, 0L) else null
    }

    override fun setLastRegistrationPackageUpdateTime(timeMs: Long) {
        prefs.edit { putLong(KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME, timeMs) }
    }

    override fun saveCachedConfig(config: GeofenceConfig) =
        writeJson(KEY_CACHED_CONFIG, GeofenceConfig.serializer(), config)

    override fun getCachedConfig(): GeofenceConfig? =
        readJson(KEY_CACHED_CONFIG, GeofenceConfig.serializer())

    override fun saveLastApiFetchLocation(location: GeofenceLocation) =
        writeEncryptedJson(KEY_LAST_API_FETCH_LOCATION, GeofenceLocation.serializer(), location)

    override fun getLastApiFetchLocation(): GeofenceLocation? =
        readEncryptedJson(KEY_LAST_API_FETCH_LOCATION, GeofenceLocation.serializer())

    override fun saveLastMovementTriggerLocation(location: GeofenceLocation) =
        writeEncryptedJson(KEY_LAST_MOVEMENT_TRIGGER_LOCATION, GeofenceLocation.serializer(), location)

    override fun getLastMovementTriggerLocation(): GeofenceLocation? =
        readEncryptedJson(KEY_LAST_MOVEMENT_TRIGGER_LOCATION, GeofenceLocation.serializer())

    override fun clearLastMovementTriggerLocation() {
        prefs.edit { remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION) }
    }

    override fun getLastSyncTimestamp(): Long? = prefs.read {
        if (contains(KEY_LAST_SYNC)) getLong(KEY_LAST_SYNC, 0L) else null
    }

    override fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, timestamp) }
    }

    override fun clearUserScopedState() {
        prefs.edit {
            remove(KEY_LAST_API_FETCH_LOCATION)
            remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION)
            remove(KEY_REGISTERED_IDS)
            remove(KEY_ENTERED_IDS)
            remove(KEY_LAST_REGISTRATION_UPTIME)
            remove(KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME)
            remove(KEY_LAST_SYNC)
        }
    }

    override fun clearAll() {
        prefs.edit { clear() }
    }

    private fun <T> writeJson(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit { putString(key, jsonSerializer.encode(serializer, value)) }
    }

    /**
     * Returns the decoded value, or `null` if absent or unparseable. On parse
     * failure the key is wiped so a stale value won't keep failing on every
     * read. Read and remove are sequenced separately so the write doesn't
     * nest inside the read block.
     */
    private fun <T> readJson(key: String, serializer: KSerializer<T>): T? {
        val raw = prefs.read { getString(key, null) } ?: return null
        return jsonSerializer.decodeOrNull(serializer, raw) ?: run {
            prefs.edit { remove(key) }
            null
        }
    }

    private fun <T> writeEncryptedJson(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit { putString(key, crypto.encrypt(jsonSerializer.encode(serializer, value))) }
    }

    /**
     * Mirrors [readJson] but transparently decrypts via [PreferenceCrypto].
     * On Keystore failure (unavailable, OEM bug), `decrypt` returns the input
     * as-is and the JSON parse decides if it's readable — same self-healing
     * wipe path as [readJson] for unparseable payloads.
     */
    private fun <T> readEncryptedJson(key: String, serializer: KSerializer<T>): T? {
        val raw = prefs.read { getString(key, null) } ?: return null
        return jsonSerializer.decodeOrNull(serializer, crypto.decrypt(raw)) ?: run {
            prefs.edit { remove(key) }
            null
        }
    }

    private val enteredLock = Any()

    private companion object {
        const val KEY_CACHED_REGIONS = "cached_regions"
        const val KEY_REGISTERED_IDS = "registered_ids"
        const val KEY_ENTERED_IDS = "entered_ids"
        const val KEY_CACHED_CONFIG = "cached_config"
        const val KEY_LAST_API_FETCH_LOCATION = "last_api_fetch_location"
        const val KEY_LAST_MOVEMENT_TRIGGER_LOCATION = "last_movement_trigger_location"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val KEY_LAST_REGISTRATION_UPTIME = "last_registration_uptime"
        const val KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME = "last_registration_package_update_time"
        const val CRYPTO_KEY_ALIAS = "cio_geofence_location_key"
        val REGIONS_SERIALIZER = ListSerializer(GeofenceRegion.serializer())
        val ID_SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
