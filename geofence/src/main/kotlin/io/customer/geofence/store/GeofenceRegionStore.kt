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
 * Cached regions and config survive sign-out: the fetch is workspace-scoped (no userId on the wire).
 * Location anchors and queued polygon approach fixes are encrypted with [PreferenceCrypto].
 */
internal interface GeofenceRegionStore {
    fun appendPendingPolygonApproachBatches(entries: List<PendingPolygonApproachBatch>): Boolean
    fun getPendingPolygonApproachBatches(): List<PendingPolygonApproachBatch>
    fun removePendingPolygonApproachBatch(id: String): Boolean

    fun saveCachedRegions(regions: List<GeofenceRegion>)
    fun getCachedRegions(): List<GeofenceRegion>

    /** Current server-catalog region; excludes retained cleanup-only definitions. */
    fun getCachedRegion(id: String): GeofenceRegion? = getCachedRegions().find { it.id == id }

    /** Definition for an OS registration, including one retained after its removal failed. */
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

    /** Fencing token for user-scoped writes; bumped when a session opens and when a reset completes. */
    fun userStateGeneration(): Long

    fun activeUserSessionId(): String?

    /** Invalidates in-flight transition work when the identified profile changes. */
    fun beginUserSession(userId: String)

    /**
     * Reads [currentUserId] under the session lock so a concurrent identify can't be overwritten by an
     * older read. For callers that don't own the identity (app launch, boot restore, OS callbacks).
     */
    fun beginUserSessionForCurrentUser(currentUserId: () -> String?)

    /**
     * Opens a session only while none is recorded. For callers that read the user before calling: a
     * concurrent identify can land in between, and this must not undo it by reopening the older user.
     */
    fun beginUserSessionIfAbsent(userId: String)

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

    /**
     * Writes only while [expectedUserStateGeneration] is current, so a refresh that began before an
     * identify can't re-arm the previous user's registrations.
     */
    fun saveRoutableRegisteredIdsIfCurrent(ids: Set<String>, expectedUserStateGeneration: Long): Boolean

    /** Empty until a pass arms it: a registration only routes once a session has claimed it. */
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

    /** Fences the device is known to be inside; drives the EXIT guard ([claimExit]). */
    fun getEnteredIds(): Set<String>

    fun recordEntered(geofenceId: String)

    /**
     * Atomically drops [geofenceId] and its reported-ENTER mark. `false` means no record of the device
     * being inside, so the EXIT is a GMS reconciliation artifact rather than a crossing.
     */
    fun claimExit(geofenceId: String): Boolean

    /** Reported-transition counter, read before a sync computes its geometry and passed to [reconcileEnteredIds]. */
    fun containmentEpoch(): Long

    /**
     * Prunes to [registeredIds] and unions in [inside], since a fix can miss containment the OS already
     * reported; a null [inside] only prunes. Against [sinceEpoch], later exits aren't re-added and
     * [resetIds] entered later keep their record. Returns the [resetIds] dropped.
     */
    fun reconcileEnteredIds(
        registeredIds: Set<String>,
        inside: Set<String>?,
        sinceEpoch: Long,
        resetIds: Set<String> = emptySet()
    ): Set<String>

    /**
     * False on an install upgraded from a version that predates the set, until the first pass with a
     * live fix. The EXIT guard defers while false, since "empty" and "no data yet" look the same.
     */
    fun hasContainmentRecord(): Boolean

    /**
     * Like [hasContainmentRecord], tells "no data yet" (an upgraded install) from "never reported", so
     * the first genuine EXIT per fence isn't dropped.
     */
    fun hasEmittedEnterRecord(userId: String): Boolean

    /**
     * Whether an ENTER for [geofenceId] was reported for [userId] with no EXIT since. Kept apart from
     * [getEnteredIds] (where the device is) because a sync can seed containment before the OS reports
     * the matching ENTER. Per user because a direct A-to-B identify publishes no `ResetEvent`.
     */
    fun hasEmittedEnter(userId: String, geofenceId: String): Boolean

    fun markEnterEmitted(userId: String, geofenceId: String)

    /**
     * A fence dropped while the device is inside never reports the EXIT that would clear its mark,
     * which would then swallow a genuine revisit.
     */
    fun pruneEmittedEnterIds(registeredIds: Set<String>)

    /** Uptime at the last successful OS registration, for reboot detection. */
    fun getLastRegistrationUptime(): Long?
    fun setLastRegistrationUptime(uptimeMs: Long)

    /** Package lastUpdateTime at the last successful OS registration, for app-update detection. */
    fun getLastRegistrationPackageUpdateTime(): Long?
    fun setLastRegistrationPackageUpdateTime(timeMs: Long)

    fun saveCachedConfig(config: GeofenceConfig)
    fun getCachedConfig(): GeofenceConfig?

    fun getLastApiFetchLocation(): GeofenceLocation?

    fun saveLastMovementTriggerLocation(location: GeofenceLocation, radiusMeters: Float)

    /**
     * Writes only while [expectedUserStateGeneration] is current, so a sign-out during the unlocked OS
     * registration await isn't overwritten with the departing user's position.
     */
    fun saveLastMovementTriggerLocationIfCurrent(
        location: GeofenceLocation,
        radiusMeters: Float,
        expectedUserStateGeneration: Long
    ): Boolean

    fun getLastMovementTriggerLocation(): GeofenceLocation?

    /**
     * Radius actually registered, which a polygon can shrink below the configured one. Null before the
     * first registration writes it, including the first pass after an upgrade.
     */
    fun getLastMovementTriggerRadius(): Float?
    fun clearLastMovementTriggerLocation()

    fun getLastSyncTimestamp(): Long?

    /**
     * Writes only while [expectedUserStateGeneration] is current. Anchor and timestamp describe one
     * fetch, and the check shares [beginUserSession]'s lock so an identify cannot land between them.
     */
    fun saveApiFetchStateIfCurrent(
        location: GeofenceLocation,
        syncTimestamp: Long,
        expectedUserStateGeneration: Long
    ): Boolean

    /** Sign-out wipe of user-scoped state, including OS registration IDs. */
    fun clearUserScopedState()

    /** Stops user-scoped work after OS cleanup fails while retaining IDs needed to retry removal. */
    fun clearUserSessionRetainingOsRegistrations()

    /**
     * If a newer identify has already advanced the generation, its owner is preserved while the
     * departing user's state is still cleared.
     */
    fun completeUserReset(expectedUserStateGeneration: Long, osRegistrationsCleared: Boolean)

    fun clearAll()
}

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
            !hasActiveUserSessionLocked()
        ) {
            return@synchronized false
        }
        val incomingIds = entries.mapTo(mutableSetOf(), PendingPolygonApproachBatch::id)
        val retained = readPendingPolygonApproachBatches().filterNot { it.id in incomingIds }
        // When full, keep the oldest route segments and reject the newer batch. Returning false lets
        // PolygonApproachReceiver evaluate these locations in-process instead of losing them.
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
            @Suppress("ApplySharedPref", "UseKtx")
            prefs.edit().remove(KEY_PENDING_POLYGON_APPROACH_BATCHES).commit()
        } else {
            writeEncryptedJsonCommitted(
                KEY_PENDING_POLYGON_APPROACH_BATCHES,
                PENDING_APPROACH_BATCHES_SERIALIZER,
                retained
            )
        }
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
        @Suppress("UseKtx")
        val editor = prefs.edit()
        if (pending.isEmpty()) {
            editor.remove(KEY_PENDING_TRANSITION_ENTRIES)
        } else {
            editor.putString(
                KEY_PENDING_TRANSITION_ENTRIES,
                jsonSerializer.encode(PENDING_TRANSITIONS_SERIALIZER, pending)
            )
        }
        @Suppress("ApplySharedPref")
        editor.commit()
    }

    override fun userStateGeneration(): Long = synchronized(enteredLock) {
        currentUserStateGenerationLocked()
    }

    private fun hasActiveUserSessionLocked(): Boolean =
        !prefs.read { getString(KEY_USER_STATE_OWNER, null) }.isNullOrEmpty()

    override fun activeUserSessionId(): String? = synchronized(enteredLock) {
        prefs.read { getString(KEY_USER_STATE_OWNER, null) }?.takeIf { it.isNotEmpty() }
    }

    override fun beginUserSession(userId: String) = synchronized(enteredLock) {
        beginUserSessionLocked(userId)
    }

    override fun beginUserSessionForCurrentUser(currentUserId: () -> String?) =
        synchronized(enteredLock) {
            val userId = currentUserId()?.takeIf { it.isNotEmpty() } ?: return@synchronized
            beginUserSessionLocked(userId)
        }

    private fun beginUserSessionLocked(userId: String) {
        val currentOwner = prefs.read { getString(KEY_USER_STATE_OWNER, null) }
        if (currentOwner == userId) return
        // An absent owner (install upgraded from a version without these keys) records nobody, so
        // user-scoped state is dropped rather than attributed to whoever is identified now.
        if (currentOwner.isNullOrEmpty()) {
            adoptUnownedSessionLocked(userId)
        } else {
            openUserSessionLocked(userId)
        }
    }

    override fun beginUserSessionIfAbsent(userId: String) = synchronized(enteredLock) {
        if (hasActiveUserSessionLocked()) return@synchronized
        adoptUnownedSessionLocked(userId)
    }

    /**
     * Unlike a user switch, keeps the location anchors: boot restore has no live fix or network, and
     * without them the first reboot after an upgrade registers nothing.
     */
    private fun adoptUnownedSessionLocked(userId: String) {
        openUserSessionLocked(userId, clearedKeys = SESSION_SCOPED_KEYS)
    }

    private fun openUserSessionLocked(
        userId: String,
        clearedKeys: List<String> = SESSION_SCOPED_KEYS + REGISTRATION_ANCHOR_KEYS
    ) {
        val nextGeneration = currentUserStateGenerationLocked() + 1L
        prefs.edit(commit = true) {
            putString(KEY_USER_STATE_OWNER, userId)
            putLong(KEY_USER_STATE_GENERATION, nextGeneration)
            putString(KEY_ROUTABLE_REGISTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, emptySet()))
            clearedKeys.forEach(::remove)
        }
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

    override fun saveRoutableRegisteredIdsIfCurrent(
        ids: Set<String>,
        expectedUserStateGeneration: Long
    ): Boolean = synchronized(enteredLock) {
        if (expectedUserStateGeneration != currentUserStateGenerationLocked()) return@synchronized false
        writeJson(KEY_ROUTABLE_REGISTERED_IDS, ID_SET_SERIALIZER, ids)
        true
    }

    override fun getRoutableRegisteredIds(): Set<String> =
        readJson(KEY_ROUTABLE_REGISTERED_IDS, ID_SET_SERIALIZER) ?: emptySet()

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

    // Mutators share a lock: each is a read-modify-write, racing the receiver against the sync path.
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
        inside: Set<String>?,
        sinceEpoch: Long,
        resetIds: Set<String>
    ): Set<String> = synchronized(enteredLock) {
        val stillInside = inside.orEmpty().filter { (exitEpochByGeofenceId[it] ?: 0L) <= sinceEpoch }
        // An entry reported since the caller's fix describes the geometry being registered now, so
        // it survives a reset aimed at the geometry it replaced.
        val dropped = resetIds.filter { (enterEpochByGeofenceId[it] ?: 0L) <= sinceEpoch }.toSet() - stillInside
        // A live fix ends the grace even when it finds nothing inside. A prune-only pass creating the
        // key would arm the EXIT guard off an anchor and drop crossings that predate any record.
        if (inside != null || hasContainmentRecord()) {
            val carried = (getEnteredIds() intersect registeredIds) - dropped
            writeJson(KEY_ENTERED_IDS, ID_SET_SERIALIZER, carried + stillInside)
        }
        // Bound the maps, after the filters have read them.
        exitEpochByGeofenceId.keys.retainAll(registeredIds)
        enterEpochByGeofenceId.keys.retainAll(registeredIds)
        dropped
    }

    override fun hasEmittedEnterRecord(userId: String): Boolean = synchronized(enteredLock) {
        readEmittedEnterOwner() == userId && prefs.read { contains(KEY_EMITTED_ENTER_IDS) } == true
    }

    override fun hasEmittedEnter(userId: String, geofenceId: String): Boolean = synchronized(enteredLock) {
        if (readEmittedEnterOwner() != userId) return@synchronized false
        geofenceId in readEmittedEnterIds()
    }

    override fun markEnterEmitted(userId: String, geofenceId: String) = synchronized(enteredLock) {
        // A different owner makes the set stale, so replace rather than add.
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

    private fun saveLastApiFetchLocation(location: GeofenceLocation) =
        writeEncryptedJson(KEY_LAST_API_FETCH_LOCATION, GeofenceLocation.serializer(), location)

    override fun getLastApiFetchLocation(): GeofenceLocation? =
        readEncryptedJson(KEY_LAST_API_FETCH_LOCATION, GeofenceLocation.serializer())

    override fun saveLastMovementTriggerLocation(location: GeofenceLocation, radiusMeters: Float) {
        // One edit: a centre without its radius would read as the configured radius and lose the
        // polygon shrink.
        val encrypted = locationCrypto.encrypt(
            jsonSerializer.encode(GeofenceLocation.serializer(), location)
        )
        prefs.edit {
            putString(KEY_LAST_MOVEMENT_TRIGGER_LOCATION, encrypted)
            putFloat(KEY_LAST_MOVEMENT_TRIGGER_RADIUS, radiusMeters)
        }
    }

    override fun saveLastMovementTriggerLocationIfCurrent(
        location: GeofenceLocation,
        radiusMeters: Float,
        expectedUserStateGeneration: Long
    ): Boolean = synchronized(enteredLock) {
        if (expectedUserStateGeneration != currentUserStateGenerationLocked()) return@synchronized false
        saveLastMovementTriggerLocation(location, radiusMeters)
        true
    }

    override fun getLastMovementTriggerLocation(): GeofenceLocation? =
        readEncryptedJson(KEY_LAST_MOVEMENT_TRIGGER_LOCATION, GeofenceLocation.serializer())

    override fun getLastMovementTriggerRadius(): Float? = prefs.read {
        if (contains(KEY_LAST_MOVEMENT_TRIGGER_RADIUS)) {
            getFloat(KEY_LAST_MOVEMENT_TRIGGER_RADIUS, 0f)
        } else {
            null
        }
    }

    override fun clearLastMovementTriggerLocation() {
        prefs.edit {
            remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION)
            remove(KEY_LAST_MOVEMENT_TRIGGER_RADIUS)
        }
    }

    override fun getLastSyncTimestamp(): Long? = prefs.read {
        if (contains(KEY_LAST_SYNC)) getLong(KEY_LAST_SYNC, 0L) else null
    }

    private fun setLastSyncTimestamp(timestamp: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, timestamp) }
    }

    override fun saveApiFetchStateIfCurrent(
        location: GeofenceLocation,
        syncTimestamp: Long,
        expectedUserStateGeneration: Long
    ): Boolean = synchronized(enteredLock) {
        if (expectedUserStateGeneration != currentUserStateGenerationLocked()) return@synchronized false
        saveLastApiFetchLocation(location)
        setLastSyncTimestamp(syncTimestamp)
        true
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
        prefs.edit(commit = true) {
            remove(KEY_LAST_API_FETCH_LOCATION)
            remove(KEY_LAST_MOVEMENT_TRIGGER_LOCATION)
            remove(KEY_LAST_MOVEMENT_TRIGGER_RADIUS)
            remove(KEY_PENDING_TRANSITION_ENTRIES)
            remove(KEY_PENDING_POLYGON_APPROACH_BATCHES)
            remove(KEY_ACTIVE_POLYGON_IDS)
            remove(KEY_COARSE_INSIDE_POLYGON_IDS)
            remove(KEY_ENTERED_IDS)
            remove(KEY_EMITTED_ENTER_IDS)
            remove(KEY_EMITTED_ENTER_OWNER)
            remove(KEY_LAST_SYNC)
            if (osRegistrationsCleared) {
                remove(KEY_REGISTERED_IDS)
                remove(KEY_ROUTABLE_REGISTERED_IDS)
                remove(KEY_RETAINED_REGISTERED_REGIONS)
                remove(KEY_LAST_REGISTRATION_UPTIME)
                remove(KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME)
            } else {
                putString(KEY_ROUTABLE_REGISTERED_IDS, jsonSerializer.encode(ID_SET_SERIALIZER, emptySet()))
            }
            if (!resetWasSuperseded) {
                remove(KEY_USER_STATE_OWNER)
                putLong(KEY_USER_STATE_GENERATION, currentGeneration + 1L)
            }
        }
        Unit
    }

    override fun clearAll() {
        prefs.edit { clear() }
    }

    private fun <T> writeJson(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit { putString(key, jsonSerializer.encode(serializer, value)) }
    }

    /**
     * An unparseable value is wiped so it doesn't fail every read. The remove runs after the read
     * block so the write doesn't nest inside it.
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
        // Exact route history must never take PreferenceCrypto's plaintext fallback (pre-API 23 or
        // Keystore failure); the receiver evaluates the batch in-process instead.
        if (encrypted == plaintext) return false
        // Explicit commit, not the KTX edit {}: this returns whether the write reached disk.
        @Suppress("UseKtx")
        return prefs.edit().putString(key, encrypted).commit()
    }

    /** On Keystore failure `decrypt` returns the input as-is, and the parse decides if it's readable. */
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

    // Guarded by [enteredLock]. Bumped per reported transition; the maps hold each fence's last epoch
    // per direction, so a sync can tell what was reported after the fix it holds.
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
        const val KEY_LAST_MOVEMENT_TRIGGER_RADIUS = "last_movement_trigger_radius"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val KEY_LAST_REGISTRATION_UPTIME = "last_registration_uptime"
        const val KEY_LAST_REGISTRATION_PACKAGE_UPDATE_TIME = "last_registration_package_update_time"

        // Cleared whenever a session opens. Includes the sync stamp so the next session re-fetches.
        val SESSION_SCOPED_KEYS = listOf(
            KEY_PENDING_TRANSITION_ENTRIES,
            KEY_PENDING_POLYGON_APPROACH_BATCHES,
            KEY_ACTIVE_POLYGON_IDS,
            KEY_COARSE_INSIDE_POLYGON_IDS,
            KEY_ENTERED_IDS,
            KEY_EMITTED_ENTER_IDS,
            KEY_EMITTED_ENTER_OWNER,
            KEY_LAST_SYNC
        )

        val REGISTRATION_ANCHOR_KEYS = listOf(
            KEY_LAST_API_FETCH_LOCATION,
            KEY_LAST_MOVEMENT_TRIGGER_LOCATION,
            KEY_LAST_MOVEMENT_TRIGGER_RADIUS
        )

        const val MAXIMUM_PENDING_APPROACH_BATCHES = 128
        val REGIONS_SERIALIZER = ListSerializer(GeofenceRegion.serializer())
        val PENDING_TRANSITIONS_SERIALIZER = ListSerializer(PendingGeofenceDelivery.serializer())
        val PENDING_APPROACH_BATCHES_SERIALIZER =
            ListSerializer(PendingPolygonApproachBatch.serializer())
        val ID_SET_SERIALIZER = SetSerializer(String.serializer())
    }
}
