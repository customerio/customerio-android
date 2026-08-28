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
 *                                  describes.
 *   emittedEnterIds             — fences reported entered, plus the userId they belong to.
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
     * Atomically drops [geofenceId] from the entered set, returning whether it was there, and on
     * `true` drops the reported-ENTER mark in the same step.
     *
     * `false` means no record of the device ever being inside, so the EXIT is a GMS reconciliation
     * artifact rather than a crossing.
     */
    fun claimExit(geofenceId: String): Boolean

    /** Reported-transition counter, read before a sync computes its geometry and passed to [reconcileEnteredIds]. */
    fun containmentEpoch(): Long

    /**
     * Prunes the entered set to [registeredIds] and unions in [inside], the fences our own geometry
     * puts the device within at registration time. Union rather than replace, because [inside] may
     * come from a stale anchor whose "outside" must not erase real containment.
     *
     * [sinceEpoch] is [containmentEpoch] as of the fix [inside] was derived from; a fence whose exit
     * was claimed after that is dropped, so a departure reported during the sync's GMS await wins
     * over the older geometry.
     *
     * [resetIds] have their carried record dropped rather than pruned and kept, for fences the caller
     * knows the stored containment no longer describes; [inside] still re-adds them. A fence that
     * reported an entry after [sinceEpoch] keeps its record — the OS placing the device inside
     * outranks the caller's older geometry.
     *
     * Returns the [resetIds] whose record was actually dropped, so a caller resetting related state
     * can act on the same decision instead of its own guess.
     */
    fun reconcileEnteredIds(
        registeredIds: Set<String>,
        inside: Set<String>,
        sinceEpoch: Long,
        resetIds: Set<String> = emptySet()
    ): Set<String>

    /**
     * Whether containment has ever been recorded. False on an install upgraded from a version that
     * predates the set, until the first registration seeds it — the EXIT guard defers while it is,
     * since "empty" and "no data yet" are otherwise indistinguishable.
     */
    fun hasContainmentRecord(): Boolean

    /**
     * Fences reported entered with no EXIT reported since — what the backend currently believes.
     * Distinct from [getEnteredIds], which tracks where the device *is*: a sync seeds containment
     * 20-46ms before the OS reports the matching ENTER, so one set would swallow real crossings.
     *
     * Scoped to [userId] because a direct A-to-B identify publishes no `ResetEvent`. Set by
     * [markEnterEmitted], cleared by [claimExit].
     */
    fun hasEmittedEnter(userId: String, geofenceId: String): Boolean

    /** Records that an ENTER for [geofenceId] reached the delivery pipeline for [userId]. Idempotent. */
    fun markEnterEmitted(userId: String, geofenceId: String)

    /**
     * Drops reported-ENTER marks for fences no longer in [registeredIds]. A fence dropped while the
     * device is inside never reports the EXIT that would clear its mark, which would then swallow a
     * genuine revisit.
     */
    fun pruneEmittedEnterIds(registeredIds: Set<String>)

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

    // The mutators share a lock: each is a read-modify-write, and transitions arrive on the
    // receiver's coroutine while registration runs on the sync path.
    override fun recordEntered(geofenceId: String) = synchronized(enteredLock) {
        val current = getEnteredIds()
        if (geofenceId !in current) {
            writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, current + geofenceId)
        }
        // Stamped even when the id is already recorded: a repeat report still says the OS puts the
        // device inside now, which a sync holding an older fix has to defer to.
        epoch += 1
        enterEpochByGeofenceId[geofenceId] = epoch
    }

    override fun claimExit(geofenceId: String): Boolean = synchronized(enteredLock) {
        val current = getEnteredIds()
        if (geofenceId !in current) return@synchronized false
        val marks = readEmittedEnterIds()
        // One commit: a mark stranded by a torn write would silence the next genuine arrival.
        prefs.edit {
            putString(KEY_ENTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, current - geofenceId))
            if (geofenceId in marks) {
                putString(KEY_EMITTED_ENTER_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, marks - geofenceId))
            }
        }
        // Only a consumed record bumps the epoch; a claim that found nothing is a suspected GMS
        // artifact and must not block the geometry seed.
        epoch += 1
        exitEpochByGeofenceId[geofenceId] = epoch
        true
    }

    override fun containmentEpoch(): Long = synchronized(enteredLock) { epoch }

    override fun reconcileEnteredIds(
        registeredIds: Set<String>,
        inside: Set<String>,
        sinceEpoch: Long,
        resetIds: Set<String>
    ): Set<String> = synchronized(enteredLock) {
        val stillInside = inside.filter { (exitEpochByGeofenceId[it] ?: 0L) <= sinceEpoch }
        // An entry reported since the caller's fix describes the geometry being registered now, so
        // it survives a reset aimed at the geometry it replaced.
        val dropped = resetIds.filter { (enterEpochByGeofenceId[it] ?: 0L) <= sinceEpoch }.toSet() - stillInside
        val carried = (getEnteredIds() intersect registeredIds) - dropped
        writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, carried + stillInside)
        // Bound the maps, after the filters have read them.
        exitEpochByGeofenceId.keys.retainAll(registeredIds)
        enterEpochByGeofenceId.keys.retainAll(registeredIds)
        dropped
    }

    override fun hasEmittedEnter(userId: String, geofenceId: String): Boolean = synchronized(enteredLock) {
        if (readEmittedEnterOwner() != userId) return@synchronized false
        geofenceId in readEmittedEnterIds()
    }

    override fun markEnterEmitted(userId: String, geofenceId: String) = synchronized(enteredLock) {
        // One identity owns the set at a time, so a different owner makes it stale — replace, not add.
        val sameOwner = readEmittedEnterOwner() == userId
        val current = if (sameOwner) readEmittedEnterIds() else emptySet()
        if (sameOwner && geofenceId in current) return@synchronized
        // One commit: a death between separate writes leaves the new owner holding the old set.
        prefs.edit {
            if (!sameOwner) {
                putString(KEY_EMITTED_ENTER_OWNER, userId)
            }
            putString(KEY_EMITTED_ENTER_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, current + geofenceId))
        }
    }

    private fun readEmittedEnterIds(): Set<String> =
        readJson(KEY_EMITTED_ENTER_IDS, ID_SET_SERIALIZER) ?: emptySet()

    private fun readEmittedEnterOwner(): String? = prefs.read { getString(KEY_EMITTED_ENTER_OWNER, null) }

    override fun pruneEmittedEnterIds(registeredIds: Set<String>) = synchronized(enteredLock) {
        val current = readEmittedEnterIds()
        val retained = current intersect registeredIds
        if (retained.size != current.size) {
            writeJson(KEY_EMITTED_ENTER_IDS, ID_SET_SERIALIZER, retained)
        }
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
            remove(KEY_EMITTED_ENTER_IDS)
            remove(KEY_EMITTED_ENTER_OWNER)
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

    // Guarded by [enteredLock]. Bumped per reported transition, so a sync can tell which of the two
    // directions a fence reported most recently against the fix the sync is holding. Per-fence
    // values are the epoch at which that fence last reported in that direction.
    private var epoch = 0L
    private val exitEpochByGeofenceId = mutableMapOf<String, Long>()
    private val enterEpochByGeofenceId = mutableMapOf<String, Long>()

    private companion object {
        const val KEY_CACHED_REGIONS = "cached_regions"
        const val KEY_REGISTERED_IDS = "registered_ids"
        const val KEY_ENTERED_IDS = "entered_ids"
        const val KEY_EMITTED_ENTER_IDS = "emitted_enter_ids"
        const val KEY_EMITTED_ENTER_OWNER = "emitted_enter_owner"
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
