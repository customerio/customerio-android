package io.customer.geofence.store

import android.content.Context
import androidx.core.content.edit
import io.customer.geofence.GeofenceConfig
import io.customer.geofence.GeofenceJsonSerializer
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.transitionRevision
import io.customer.sdk.communication.Event
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
 *   retainedRegisteredRegions   — definitions for stale registrations whose OS removal failed;
 *                                  excluded from nearest-N, but retained for callback routing.
 *   enteredIds                  — fences the device is inside; goes with the registrations it
 *                                  describes.
 *   emittedEnterIds             — fences reported entered, plus the userId they belong to.
 *   lastApiFetchLocation        — anchor for the tier-B distance check (rarely updated).
 *   lastMovementTriggerLocation — user's location at the most recent movement-trigger
 *                                  registration; used by boot restore to re-center
 *                                  closer to the user's real position than the anchor.
 *   lastSyncTimestamp           — freshness throttle; cleared so the next login re-fetches.
 *   pendingPolygonApproachBatches — exact responsive-mode fixes awaiting ordered evaluation.
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
 * app-private. User-location snapshots and queued polygon approach fixes are encrypted at rest
 * via [PreferenceCrypto] (AES-256-GCM, Android Keystore) and cleared on sign-out.
 */
internal interface GeofenceRegionStore {
    /** Ordered exact-location batches used by responsive polygon evaluation. */
    fun appendPendingPolygonApproachBatches(entries: List<PendingPolygonApproachBatch>): Boolean
    fun getPendingPolygonApproachBatches(): List<PendingPolygonApproachBatch>
    fun removePendingPolygonApproachBatch(id: String): Boolean
    fun clearPendingPolygonApproachBatches()

    fun saveCachedRegions(regions: List<GeofenceRegion>)
    fun getCachedRegions(): List<GeofenceRegion>

    /** Current server-catalog region. Retained cleanup-only definitions are deliberately excluded. */
    fun getCachedRegion(id: String): GeofenceRegion? = getCachedRegions().find { it.id == id }

    /** Shape discriminator for an OS registration, including a failed-removal tombstone. */
    fun getRegisteredRegion(id: String): GeofenceRegion? =
        getCachedRegion(id) ?: getRetainedRegisteredRegions().find { it.id == id }

    fun saveRetainedRegisteredRegions(regions: List<GeofenceRegion>)
    fun getRetainedRegisteredRegions(): List<GeofenceRegion>

    /** Durable staging rows used to recover the crash window before the delivery outbox append. */
    fun getPendingTransitionEntries(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): List<PendingGeofenceDelivery>

    fun getAllPendingTransitionEntries(): List<PendingGeofenceDelivery>

    /**
     * Synchronously stages one attempt and commits its physical containment if
     * [expectedUserStateGeneration] is still current.
     */
    fun savePendingTransitionEntries(
        entries: List<PendingGeofenceDelivery>,
        expectedUserStateGeneration: Long
    ): Boolean
    fun clearPendingTransitionEntries(transitionId: String)

    /** Synchronously clears a staged attempt after its file-outbox rows are durable. */
    fun completePendingTransition(transitionId: String): Boolean

    /** Changes whenever sign-out clears user-scoped state. */
    fun userStateGeneration(): Long

    /** Whether polygon fine monitoring belongs to a currently identified user session. */
    fun hasActiveUserSession(): Boolean

    fun activeUserSessionId(): String?

    /** Invalidates in-flight transition work when the identified profile changes. */
    fun beginUserSession(userId: String)

    /** Atomically commits containment and clears its staged transition for this user generation. */
    fun commitBusinessTransition(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        transitionId: String?,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int? = null
    ): Boolean

    fun saveRegisteredIds(ids: Set<String>)
    fun getRegisteredIds(): Set<String>

    /** OS registrations allowed to generate business events for the current user session. */
    fun saveRoutableRegisteredIds(ids: Set<String>)
    fun getRoutableRegisteredIds(): Set<String>

    /** Polygon enclosing circles currently known to contain the device. */
    fun getActivePolygonIds(): Set<String>
    fun activatePolygon(id: String)
    fun deactivatePolygon(id: String)
    fun deactivatePolygonIfCurrent(id: String, expectedUserStateGeneration: Long): Boolean
    fun retainActivePolygonIds(ids: Set<String>)
    fun clearActivePolygonIds()
    fun getCoarseInsidePolygonIds(): Set<String>
    fun recordPolygonCoarseInside(id: String)
    fun recordPolygonCoarseOutside(id: String)
    fun retainCoarseInsidePolygonIds(ids: Set<String>)

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

    /** Stops user-scoped work after OS cleanup fails while retaining IDs needed to retry removal. */
    fun clearUserSessionRetainingOsRegistrations()

    /**
     * Completes a sign-out that started at [expectedUserStateGeneration]. If a newer identify has
     * already advanced the generation, its owner is preserved while the departing user's state is
     * still cleared.
     */
    fun completeUserReset(expectedUserStateGeneration: Long, osRegistrationsCleared: Boolean)

    fun clearAll()
}

/** Cached config, or the constant fallback when none is cached. */
internal fun GeofenceRegionStore.getCachedConfigOrFallback(): GeofenceConfig =
    getCachedConfig() ?: GeofenceConfig.fallback()

internal interface GeofenceLocationCrypto {
    fun encrypt(plaintext: String): String
    fun decrypt(encoded: String): String
}

private class PreferenceGeofenceLocationCrypto(logger: Logger) : GeofenceLocationCrypto {
    private val delegate = PreferenceCrypto("cio_geofence_location_key", logger)

    override fun encrypt(plaintext: String): String = delegate.encrypt(plaintext)
    override fun decrypt(encoded: String): String = delegate.decrypt(encoded)
}

internal class GeofenceRegionStoreImpl(
    context: Context,
    private val jsonSerializer: GeofenceJsonSerializer,
    logger: Logger,
    private val locationCrypto: GeofenceLocationCrypto = PreferenceGeofenceLocationCrypto(logger)
) : PreferenceStore(context), GeofenceRegionStore {

    override val prefsName: String by lazy {
        "io.customer.sdk.geofence_regions.${context.packageName}"
    }

    override fun appendPendingPolygonApproachBatches(
        entries: List<PendingPolygonApproachBatch>
    ): Boolean = synchronized(enteredLock) {
        if (entries.isEmpty()) return@synchronized true
        val expectedGeneration = entries.first().userStateGeneration
        if (
            entries.any { it.userStateGeneration != expectedGeneration } ||
            expectedGeneration != currentUserStateGenerationLocked() ||
            !hasActiveUserSession()
        ) {
            return@synchronized false
        }
        val incomingIds = entries.mapTo(mutableSetOf(), PendingPolygonApproachBatch::id)
        val retained = readPendingPolygonApproachBatches().filterNot { it.id in incomingIds }
        // Preserve the oldest evidence when storage is under sustained pressure: a newer fix must
        // never overtake an already-persisted route segment. But a batch that doesn't fit is a batch
        // nothing will ever replay, and reporting success for it would tell the scheduler to enqueue
        // work for locations that are not on disk — silently losing them. Report failure instead, so
        // PolygonApproachReceiver evaluates these locations in-process while it still holds them.
        if (retained.size + entries.size > MAXIMUM_PENDING_APPROACH_BATCHES) {
            return@synchronized false
        }
        writeEncryptedJsonCommitted(
            KEY_PENDING_POLYGON_APPROACH_BATCHES,
            PENDING_APPROACH_BATCHES_SERIALIZER,
            retained + entries
        )
    }

    override fun getPendingPolygonApproachBatches(): List<PendingPolygonApproachBatch> =
        synchronized(enteredLock) { readPendingPolygonApproachBatches() }

    override fun removePendingPolygonApproachBatch(id: String): Boolean = synchronized(enteredLock) {
        val entries = readPendingPolygonApproachBatches()
        val retained = entries.filterNot { it.id == id }
        if (retained.size == entries.size) return@synchronized true
        if (retained.isEmpty()) {
            prefs.edit().remove(KEY_PENDING_POLYGON_APPROACH_BATCHES).commit()
        } else {
            writeEncryptedJsonCommitted(
                KEY_PENDING_POLYGON_APPROACH_BATCHES,
                PENDING_APPROACH_BATCHES_SERIALIZER,
                retained
            )
        }
    }

    override fun clearPendingPolygonApproachBatches() = synchronized(enteredLock) {
        prefs.edit().remove(KEY_PENDING_POLYGON_APPROACH_BATCHES).commit()
        Unit
    }

    override fun saveCachedRegions(regions: List<GeofenceRegion>) = synchronized(enteredLock) {
        writeJson(KEY_CACHED_REGIONS, REGIONS_SERIALIZER, regions)
    }

    override fun getCachedRegions(): List<GeofenceRegion> =
        readJson(KEY_CACHED_REGIONS, REGIONS_SERIALIZER) ?: emptyList()

    override fun saveRetainedRegisteredRegions(regions: List<GeofenceRegion>) = synchronized(enteredLock) {
        if (regions.isEmpty()) {
            prefs.edit { remove(KEY_RETAINED_REGISTERED_REGIONS) }
        } else {
            writeJson(KEY_RETAINED_REGISTERED_REGIONS, REGIONS_SERIALIZER, regions)
        }
    }

    override fun getRetainedRegisteredRegions(): List<GeofenceRegion> =
        readJson(KEY_RETAINED_REGISTERED_REGIONS, REGIONS_SERIALIZER) ?: emptyList()

    override fun getPendingTransitionEntries(
        userId: String,
        geofenceId: String,
        transition: Event.GeofenceTransition
    ): List<PendingGeofenceDelivery> = synchronized(enteredLock) {
        readPendingTransitionEntries().filter {
            it.userId == userId && it.geofenceId == geofenceId && it.transition == transition
        }
    }

    override fun getAllPendingTransitionEntries(): List<PendingGeofenceDelivery> = synchronized(enteredLock) {
        readPendingTransitionEntries()
    }

    override fun savePendingTransitionEntries(
        entries: List<PendingGeofenceDelivery>,
        expectedUserStateGeneration: Long
    ): Boolean = synchronized(enteredLock) {
        require(entries.isNotEmpty()) { "pending transition entries cannot be empty" }
        require(entries.map(PendingGeofenceDelivery::transitionId).distinct().size == 1) {
            "pending transition entries must share a transition id"
        }
        if (expectedUserStateGeneration != currentUserStateGenerationLocked()) {
            return@synchronized false
        }
        val first = entries.first()
        if (
            first.regionRevision != null &&
            getCachedRegion(first.geofenceId)?.transitionRevision() != first.regionRevision
        ) {
            return@synchronized false
        }
        // Distinct physical crossings can repeat a direction while an older attempt is still staged
        // (ENTER → EXIT → ENTER). Replace only an idempotent replay of this exact attempt.
        val retained = readPendingTransitionEntries().filterNot {
            it.transitionId == first.transitionId
        }
        val updatedEntered = when (first.transition) {
            Event.GeofenceTransition.ENTER -> getEnteredIds() + first.geofenceId
            Event.GeofenceTransition.EXIT -> getEnteredIds() - first.geofenceId
        }
        val emittedOwner = readEmittedEnterOwner()
        val updatedEmitted = when {
            first.transition == Event.GeofenceTransition.EXIT -> readEmittedEnterIds() - first.geofenceId
            first.marksEnterReported -> {
                val sameOwner = emittedOwner == first.userId
                (if (sameOwner) readEmittedEnterIds() else emptySet()) + first.geofenceId
            }
            else -> null
        }
        val editor = prefs.edit()
            .putString(
                KEY_PENDING_TRANSITION_ENTRIES,
                jsonSerializer.encode(PENDING_TRANSITIONS_SERIALIZER, retained + entries)
            )
            .putString(KEY_ENTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, updatedEntered))
        updatedEmitted?.let {
            editor.putString(KEY_EMITTED_ENTER_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, it))
        }
        if (first.marksEnterReported && first.userId != null) {
            editor.putString(KEY_EMITTED_ENTER_OWNER, first.userId)
        }
        if (!editor.commit()) return@synchronized false

        epoch += 1
        when (first.transition) {
            Event.GeofenceTransition.ENTER -> enterEpochByGeofenceId[first.geofenceId] = epoch
            Event.GeofenceTransition.EXIT -> exitEpochByGeofenceId[first.geofenceId] = epoch
        }
        true
    }

    override fun clearPendingTransitionEntries(transitionId: String) = synchronized(enteredLock) {
        val retained = readPendingTransitionEntries().filterNot { it.transitionId == transitionId }
        if (retained.isEmpty()) {
            prefs.edit { remove(KEY_PENDING_TRANSITION_ENTRIES) }
        } else {
            writeJson(KEY_PENDING_TRANSITION_ENTRIES, PENDING_TRANSITIONS_SERIALIZER, retained)
        }
    }

    override fun completePendingTransition(transitionId: String): Boolean = synchronized(enteredLock) {
        val allPending = readPendingTransitionEntries()
        val pending = allPending.filterNot { it.transitionId == transitionId }
        if (pending.size == allPending.size) return@synchronized true
        val editor = prefs.edit()
        if (pending.isEmpty()) {
            editor.remove(KEY_PENDING_TRANSITION_ENTRIES)
        } else {
            editor.putString(
                KEY_PENDING_TRANSITION_ENTRIES,
                jsonSerializer.encode(PENDING_TRANSITIONS_SERIALIZER, pending)
            )
        }
        editor.commit()
    }

    override fun userStateGeneration(): Long = synchronized(enteredLock) {
        currentUserStateGenerationLocked()
    }

    override fun hasActiveUserSession(): Boolean = synchronized(enteredLock) {
        !prefs.read { getString(KEY_USER_STATE_OWNER, null) }.isNullOrEmpty()
    }

    override fun activeUserSessionId(): String? = synchronized(enteredLock) {
        prefs.read { getString(KEY_USER_STATE_OWNER, null) }?.takeIf { it.isNotEmpty() }
    }

    override fun beginUserSession(userId: String) = synchronized(enteredLock) {
        val currentOwner = prefs.read { getString(KEY_USER_STATE_OWNER, null) }
        if (currentOwner == userId) return@synchronized
        val nextGeneration = currentUserStateGenerationLocked() + 1L
        val hasRoutingState = prefs.read { contains(KEY_ROUTABLE_REGISTERED_IDS) } == true
        if (currentOwner == null && !hasRoutingState) {
            // Upgrade migration: older SDKs persisted a secure user and registrations but no
            // geofence-session owner/routing key. Adopt that same persisted session without
            // discarding valid OS registrations or containment before the first callback.
            prefs.edit()
                .putString(KEY_USER_STATE_OWNER, userId)
                .putLong(KEY_USER_STATE_GENERATION, nextGeneration)
                .commit()
            return@synchronized
        }
        prefs.edit()
            .putString(KEY_USER_STATE_OWNER, userId)
            .putLong(KEY_USER_STATE_GENERATION, nextGeneration)
            .putString(KEY_ROUTABLE_REGISTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, emptySet()))
            .remove(KEY_LAST_API_FETCH_LOCATION)
            .remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION)
            .remove(KEY_PENDING_TRANSITION_ENTRIES)
            .remove(KEY_PENDING_POLYGON_APPROACH_BATCHES)
            .remove(KEY_ACTIVE_POLYGON_IDS)
            .remove(KEY_COARSE_INSIDE_POLYGON_IDS)
            .remove(KEY_ENTERED_IDS)
            .remove(KEY_EMITTED_ENTER_IDS)
            .remove(KEY_EMITTED_ENTER_OWNER)
            .remove(KEY_LAST_SYNC)
            .commit()
    }

    override fun commitBusinessTransition(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        transitionId: String?,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ): Boolean = synchronized(enteredLock) {
        if (expectedUserStateGeneration != currentUserStateGenerationLocked()) return@synchronized false
        if (
            expectedRegionRevision != null &&
            getCachedRegion(geofenceId)?.transitionRevision() != expectedRegionRevision
        ) {
            return@synchronized false
        }

        val currentEntered = getEnteredIds()
        val updatedEntered = when (transition) {
            Event.GeofenceTransition.ENTER -> currentEntered + geofenceId
            Event.GeofenceTransition.EXIT -> currentEntered - geofenceId
        }
        val allPending = readPendingTransitionEntries()
        val committedAttempt = transitionId?.let { id -> allPending.firstOrNull { it.transitionId == id } }
        val pending = transitionId?.let { id ->
            allPending.filterNot { it.transitionId == id }
        } ?: allPending
        val emittedOwner = readEmittedEnterOwner()
        val emitted = when {
            transition == Event.GeofenceTransition.EXIT -> readEmittedEnterIds() - geofenceId
            committedAttempt?.marksEnterReported == true -> {
                val sameOwner = emittedOwner == committedAttempt.userId
                (if (sameOwner) readEmittedEnterIds() else emptySet()) + geofenceId
            }
            else -> null
        }
        val editor = prefs.edit()
            .putString(KEY_ENTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, updatedEntered))
        if (pending.isEmpty()) {
            editor.remove(KEY_PENDING_TRANSITION_ENTRIES)
        } else {
            editor.putString(
                KEY_PENDING_TRANSITION_ENTRIES,
                jsonSerializer.encode(PENDING_TRANSITIONS_SERIALIZER, pending)
            )
        }
        emitted?.let {
            editor.putString(KEY_EMITTED_ENTER_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, it))
        }
        if (committedAttempt?.marksEnterReported == true && committedAttempt.userId != null) {
            editor.putString(KEY_EMITTED_ENTER_OWNER, committedAttempt.userId)
        }
        if (!editor.commit()) return@synchronized false

        epoch += 1
        when (transition) {
            Event.GeofenceTransition.ENTER -> enterEpochByGeofenceId[geofenceId] = epoch
            Event.GeofenceTransition.EXIT -> exitEpochByGeofenceId[geofenceId] = epoch
        }
        true
    }

    private fun readPendingTransitionEntries(): List<PendingGeofenceDelivery> =
        readJson(KEY_PENDING_TRANSITION_ENTRIES, PENDING_TRANSITIONS_SERIALIZER) ?: emptyList()

    private fun currentUserStateGenerationLocked(): Long =
        prefs.read { getLong(KEY_USER_STATE_GENERATION, 0L) } ?: 0L

    override fun saveRegisteredIds(ids: Set<String>) =
        writeJson(KEY_REGISTERED_IDS, ID_SET_SERIALIZER, ids)

    override fun getRegisteredIds(): Set<String> =
        readJson(KEY_REGISTERED_IDS, ID_SET_SERIALIZER) ?: emptySet()

    override fun saveRoutableRegisteredIds(ids: Set<String>) =
        writeJson(KEY_ROUTABLE_REGISTERED_IDS, ID_SET_SERIALIZER, ids)

    override fun getRoutableRegisteredIds(): Set<String> {
        val hasExplicitRoutingState = prefs.read { contains(KEY_ROUTABLE_REGISTERED_IDS) } == true
        if (!hasExplicitRoutingState) return getRegisteredIds()
        return readJson(KEY_ROUTABLE_REGISTERED_IDS, ID_SET_SERIALIZER) ?: emptySet()
    }

    override fun getActivePolygonIds(): Set<String> = synchronized(activePolygonLock) {
        readJson(KEY_ACTIVE_POLYGON_IDS, ID_SET_SERIALIZER) ?: emptySet()
    }

    override fun activatePolygon(id: String) = synchronized(activePolygonLock) {
        val current = getActivePolygonIds()
        if (id !in current) writeJson(KEY_ACTIVE_POLYGON_IDS, ID_SET_SERIALIZER, current + id)
    }

    override fun deactivatePolygon(id: String) = synchronized(activePolygonLock) {
        val current = getActivePolygonIds()
        if (id in current) writeJson(KEY_ACTIVE_POLYGON_IDS, ID_SET_SERIALIZER, current - id)
    }

    override fun deactivatePolygonIfCurrent(
        id: String,
        expectedUserStateGeneration: Long
    ): Boolean = synchronized(enteredLock) {
        if (currentUserStateGenerationLocked() != expectedUserStateGeneration) {
            return@synchronized false
        }
        synchronized(activePolygonLock) {
            val current = getActivePolygonIds()
            if (id in current) writeJson(KEY_ACTIVE_POLYGON_IDS, ID_SET_SERIALIZER, current - id)
        }
        true
    }

    override fun retainActivePolygonIds(ids: Set<String>) = synchronized(activePolygonLock) {
        val retained = getActivePolygonIds() intersect ids
        if (retained.isEmpty()) {
            prefs.edit { remove(KEY_ACTIVE_POLYGON_IDS) }
        } else {
            writeJson(KEY_ACTIVE_POLYGON_IDS, ID_SET_SERIALIZER, retained)
        }
    }

    override fun clearActivePolygonIds() = synchronized(activePolygonLock) {
        prefs.edit { remove(KEY_ACTIVE_POLYGON_IDS) }
    }

    override fun getCoarseInsidePolygonIds(): Set<String> = synchronized(activePolygonLock) {
        readJson(KEY_COARSE_INSIDE_POLYGON_IDS, ID_SET_SERIALIZER) ?: emptySet()
    }

    override fun recordPolygonCoarseInside(id: String) = synchronized(activePolygonLock) {
        val current = getCoarseInsidePolygonIds()
        if (id !in current) writeJson(KEY_COARSE_INSIDE_POLYGON_IDS, ID_SET_SERIALIZER, current + id)
    }

    override fun recordPolygonCoarseOutside(id: String) = synchronized(activePolygonLock) {
        val current = getCoarseInsidePolygonIds()
        if (id in current) writeJson(KEY_COARSE_INSIDE_POLYGON_IDS, ID_SET_SERIALIZER, current - id)
    }

    override fun retainCoarseInsidePolygonIds(ids: Set<String>) = synchronized(activePolygonLock) {
        val retained = getCoarseInsidePolygonIds() intersect ids
        if (retained.isEmpty()) {
            prefs.edit { remove(KEY_COARSE_INSIDE_POLYGON_IDS) }
        } else {
            writeJson(KEY_COARSE_INSIDE_POLYGON_IDS, ID_SET_SERIALIZER, retained)
        }
    }

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
        writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, current - geofenceId)
        // Same step: a mark stranded by a later failure would silence the next genuine arrival.
        clearEnterEmitted(geofenceId)
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
        if (!sameOwner) {
            prefs.edit { putString(KEY_EMITTED_ENTER_OWNER, userId) }
        }
        if (geofenceId !in current) {
            writeJson(KEY_EMITTED_ENTER_IDS, ID_SET_SERIALIZER, current + geofenceId)
        }
    }

    private fun readEmittedEnterIds(): Set<String> =
        readJson(KEY_EMITTED_ENTER_IDS, ID_SET_SERIALIZER) ?: emptySet()

    private fun readEmittedEnterOwner(): String? = prefs.read { getString(KEY_EMITTED_ENTER_OWNER, null) }

    private fun clearEnterEmitted(geofenceId: String) = synchronized(enteredLock) {
        val current = readEmittedEnterIds()
        if (geofenceId in current) {
            writeJson(KEY_EMITTED_ENTER_IDS, ID_SET_SERIALIZER, current - geofenceId)
        }
    }

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
        completeUserReset(userStateGeneration(), osRegistrationsCleared = true)
    }

    override fun clearUserSessionRetainingOsRegistrations() {
        completeUserReset(userStateGeneration(), osRegistrationsCleared = false)
    }

    override fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ): Unit = synchronized(enteredLock) {
        val currentGeneration = currentUserStateGenerationLocked()
        val resetWasSuperseded = currentGeneration != expectedUserStateGeneration
        val editor = prefs.edit()
            .remove(KEY_LAST_API_FETCH_LOCATION)
            .remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION)
            .remove(KEY_PENDING_TRANSITION_ENTRIES)
            .remove(KEY_PENDING_POLYGON_APPROACH_BATCHES)
            .remove(KEY_ACTIVE_POLYGON_IDS)
            .remove(KEY_COARSE_INSIDE_POLYGON_IDS)
            .remove(KEY_ENTERED_IDS)
            .remove(KEY_EMITTED_ENTER_IDS)
            .remove(KEY_EMITTED_ENTER_OWNER)
            .remove(KEY_LAST_SYNC)
        if (osRegistrationsCleared) {
            editor
                .remove(KEY_REGISTERED_IDS)
                .remove(KEY_ROUTABLE_REGISTERED_IDS)
                .remove(KEY_RETAINED_REGISTERED_REGIONS)
                .remove(KEY_LAST_REGISTRATION_UPTIME)
                .remove(KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME)
        } else {
            // Explicit empty is distinct from key absence. Absence is the upgrade path for SDK
            // versions that only stored registered_ids and must remain routable until refreshed.
            editor.putString(KEY_ROUTABLE_REGISTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, emptySet()))
        }
        if (!resetWasSuperseded) {
            editor
                .remove(KEY_USER_STATE_OWNER)
                .putLong(KEY_USER_STATE_GENERATION, currentGeneration + 1L)
        }
        editor.commit()
        Unit
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
        prefs.edit { putString(key, locationCrypto.encrypt(jsonSerializer.encode(serializer, value))) }
    }

    private fun <T> writeEncryptedJsonCommitted(
        key: String,
        serializer: KSerializer<T>,
        value: T
    ): Boolean {
        val plaintext = jsonSerializer.encode(serializer, value)
        val encrypted = locationCrypto.encrypt(plaintext)
        // Exact route history must never take PreferenceCrypto's API 21/OEM plaintext fallback.
        // The receiver can evaluate the batch immediately when durable encryption is unavailable.
        if (encrypted == plaintext) return false
        return prefs.edit().putString(key, encrypted).commit()
    }

    /**
     * Mirrors [readJson] but transparently decrypts via [PreferenceCrypto].
     * On Keystore failure (unavailable, OEM bug), `decrypt` returns the input
     * as-is and the JSON parse decides if it's readable — same self-healing
     * wipe path as [readJson] for unparseable payloads.
     */
    private fun <T> readEncryptedJson(key: String, serializer: KSerializer<T>): T? {
        val raw = prefs.read { getString(key, null) } ?: return null
        return jsonSerializer.decodeOrNull(serializer, locationCrypto.decrypt(raw)) ?: run {
            prefs.edit { remove(key) }
            null
        }
    }

    private val enteredLock = Any()
    private val activePolygonLock = Any()

    private fun readPendingPolygonApproachBatches(): List<PendingPolygonApproachBatch> =
        readEncryptedJson(
            KEY_PENDING_POLYGON_APPROACH_BATCHES,
            PENDING_APPROACH_BATCHES_SERIALIZER
        ) ?: emptyList()

    // Guarded by [enteredLock]. Bumped per reported transition, so a sync can tell which of the two
    // directions a fence reported most recently against the fix the sync is holding. Per-fence
    // values are the epoch at which that fence last reported in that direction.
    private var epoch = 0L
    private val exitEpochByGeofenceId = mutableMapOf<String, Long>()
    private val enterEpochByGeofenceId = mutableMapOf<String, Long>()

    internal companion object {
        const val KEY_CACHED_REGIONS = "cached_regions"
        const val KEY_REGISTERED_IDS = "registered_ids"
        const val KEY_ROUTABLE_REGISTERED_IDS = "routable_registered_ids"
        const val KEY_RETAINED_REGISTERED_REGIONS = "retained_registered_regions"
        const val KEY_PENDING_TRANSITION_ENTRIES = "pending_transition_entries"
        const val KEY_PENDING_POLYGON_APPROACH_BATCHES = "pending_polygon_approach_batches"
        const val KEY_USER_STATE_GENERATION = "user_state_generation"
        const val KEY_USER_STATE_OWNER = "user_state_owner"
        const val KEY_ACTIVE_POLYGON_IDS = "active_polygon_ids"
        const val KEY_COARSE_INSIDE_POLYGON_IDS = "coarse_inside_polygon_ids"
        const val KEY_ENTERED_IDS = "entered_ids"
        const val KEY_EMITTED_ENTER_IDS = "emitted_enter_ids"
        const val KEY_EMITTED_ENTER_OWNER = "emitted_enter_owner"
        const val KEY_CACHED_CONFIG = "cached_config"
        const val KEY_LAST_API_FETCH_LOCATION = "last_api_fetch_location"
        const val KEY_LAST_MOVEMENT_TRIGGER_LOCATION = "last_movement_trigger_location"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val KEY_LAST_REGISTRATION_UPTIME = "last_registration_uptime"
        const val KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME = "last_registration_package_update_time"
        const val MAXIMUM_PENDING_APPROACH_BATCHES = 128
        val REGIONS_SERIALIZER = ListSerializer(GeofenceRegion.serializer())
        val PENDING_TRANSITIONS_SERIALIZER = ListSerializer(PendingGeofenceDelivery.serializer())
        val PENDING_APPROACH_BATCHES_SERIALIZER =
            ListSerializer(PendingPolygonApproachBatch.serializer())
        val ID_SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
