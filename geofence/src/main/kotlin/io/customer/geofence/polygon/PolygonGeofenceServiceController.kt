package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceLocation
import io.customer.geofence.GeofenceManager
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.GeofenceTransitionType
import io.customer.geofence.distanceTo
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.getCachedConfigOrFallback
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists coarse-circle activity and coordinates responsive polygon evaluation. */
internal class PolygonGeofenceServiceController(
    private val context: Context,
    private val store: GeofenceRegionStore,
    private val engine: PolygonLocationEngine,
    private val approachMonitor: PolygonApproachMonitor,
    private val manager: GeofenceManager,
    private val secureUserStore: SecureUserStore
) {
    private val movementTriggerPolicy = PolygonMovementTriggerPolicy()
    private val controllerLock = Any()
    private val coarseTransitionMutex = Mutex()
    private val lastCoarseTransitionElapsedNanos = mutableMapOf<String, Long>()

    /**
     * Reached on every coarse ENTER, including the ones an identify manufactures.
     *
     * Opening a session re-arms routing from empty, so nothing counts as unchanged afterwards and
     * GMS re-reports ENTER for every fence the device is already inside. The attached fix is
     * evaluated first and a decisive one ends the session immediately, so the two-minute cost lands
     * only when that fix is ambiguous — but for an app that identifies often, that is a recurring
     * cost with no movement behind it.
     *
     * It is not suppressible without weakening the guarantee: the identify cleared containment,
     * circles recover theirs from synthesis at registration, and a polygon cannot, because the sync
     * fix carries no accuracy evidence. Suppressing here would mean a newly identified user
     * standing inside a ring never gets its ENTER until they move.
     */
    suspend fun activate(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null
    ) = coarseTransitionMutex.withLock {
        if (!isCurrentRegisteredPolygon(polygonId, expectedUserStateGeneration, expectedRegionRevision)) {
            return@withLock
        }
        if (!acceptCoarseTransition(polygonId, triggeringLocation)) return@withLock
        activate(polygonId, expectedUserStateGeneration, expectedRegionRevision)
        if (triggeringLocation != null) {
            val accepted = processTriggeredLocation(triggeringLocation, expectedUserStateGeneration)
            if (accepted) {
                updateMovementTriggerFromAcceptedFix(
                    triggeringLocation,
                    expectedUserStateGeneration
                )
            }
        }
    }

    fun activate(
        polygonId: String,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null
    ) = synchronized(controllerLock) {
        if (!isCurrentRegisteredPolygonLocked(
                polygonId,
                expectedUserStateGeneration,
                expectedRegionRevision
            )
        ) {
            return@synchronized
        }
        val alreadyActive = polygonId in store.getActivePolygonIds()
        store.recordPolygonCoarseInside(polygonId)
        store.activatePolygon(polygonId)
        if (!alreadyActive) engine.activate(polygonId)
        approachMonitor.start(expectedUserStateGeneration)
    }

    fun deactivate(
        polygonId: String,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ) = synchronized(controllerLock) {
        store.recordPolygonCoarseOutside(polygonId)
        store.deactivatePolygon(polygonId)
        engine.deactivate(polygonId)
        if (store.getActivePolygonIds().isEmpty()) {
            engine.stop()
            approachMonitor.stop(expectedUserStateGeneration)
        }
    }

    suspend fun onCoarseExit(
        polygonId: String,
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration(),
        expectedRegionRevision: Int? = null
    ) = coarseTransitionMutex.withLock {
        if (!isCurrentRegisteredPolygon(polygonId, expectedUserStateGeneration, expectedRegionRevision)) {
            return@withLock
        }
        if (!acceptCoarseTransition(polygonId, triggeringLocation)) return@withLock
        val recordedCoarseExit = synchronized(controllerLock) {
            if (!isCurrentRegisteredPolygonLocked(
                    polygonId,
                    expectedUserStateGeneration,
                    expectedRegionRevision
                )
            ) {
                false
            } else {
                store.recordPolygonCoarseOutside(polygonId)
                true
            }
        }
        if (!recordedCoarseExit) return@withLock
        if (triggeringLocation != null) {
            val accepted = processTriggeredLocation(triggeringLocation, expectedUserStateGeneration)
            if (accepted) {
                updateMovementTriggerFromAcceptedFix(
                    triggeringLocation,
                    expectedUserStateGeneration
                )
            }
        }
        synchronized(controllerLock) {
            if (!isCurrentRegisteredPolygonLocked(
                    polygonId,
                    expectedUserStateGeneration,
                    expectedRegionRevision
                )
            ) {
                return@synchronized
            }
            // A catalog refresh can activate from a newer live fix while the triggering fix is
            // evaluated. Never let this older EXIT tear that newer session down.
            if (polygonId in store.getCoarseInsidePolygonIds()) return@synchronized
            if (polygonId in store.getEnteredIds()) {
                store.activatePolygon(polygonId)
                approachMonitor.start(expectedUserStateGeneration)
            } else {
                deactivate(polygonId, expectedUserStateGeneration)
            }
        }
    }

    fun resetEvidence(polygonId: String) = synchronized(controllerLock) {
        engine.resetEvidence(polygonId)
    }

    /** Evaluates the accepted fix that fired the SDK-wide shared movement trigger. */
    suspend fun onMovementTriggerExit(
        triggeringLocation: Location?,
        expectedUserStateGeneration: Long = store.userStateGeneration()
    ): Float? {
        if (triggeringLocation == null) return null
        val fix = triggeringLocation.toPolygonLocationFix()
        val activated = synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                return@synchronized false
            }
            val activeIds = store.getActivePolygonIds()
            val enteredIds = store.getEnteredIds()
            val polygonIds = store.getRoutableRegisteredIds().filterTo(mutableSetOf()) { id ->
                val region = store.getCachedRegion(id)
                region?.isPolygon == true &&
                    (
                        id in activeIds ||
                            id in enteredIds ||
                            fix != null &&
                            region.distanceTo(
                                triggeringLocation.latitude,
                                triggeringLocation.longitude
                            ) - fix.sample.horizontalAccuracyMeters <= region.radius
                        )
            }
            polygonIds.forEach { polygonId ->
                if (polygonId !in store.getActivePolygonIds()) {
                    store.activatePolygon(polygonId)
                    engine.activate(polygonId)
                }
            }
            if (polygonIds.isNotEmpty()) approachMonitor.start(expectedUserStateGeneration)
            polygonIds.isNotEmpty()
        }
        if (!activated) return null
        val accepted = processTriggeredLocation(triggeringLocation, expectedUserStateGeneration)
        return if (accepted) {
            updateMovementTriggerFromAcceptedFix(
                triggeringLocation,
                expectedUserStateGeneration
            )
        } else {
            null
        }
    }

    fun reconcileRegisteredPolygons(ids: Set<String>) = synchronized(controllerLock) {
        val removed = store.getActivePolygonIds() - ids
        store.retainActivePolygonIds(ids)
        store.retainCoarseInsidePolygonIds(ids)
        removed.forEach(engine::deactivate)
        lastCoarseTransitionElapsedNanos.keys.retainAll(ids)
        if (store.getActivePolygonIds().isEmpty() || !hasMatchingIdentifiedUserLocked()) {
            approachMonitor.stop(store.userStateGeneration())
        }
        if (store.getActivePolygonIds().isEmpty()) engine.stop()
    }

    fun recover() = synchronized(controllerLock) {
        if (!hasMatchingIdentifiedUserLocked()) {
            invalidatePersistedCoarseState()
            return@synchronized
        }
        if (osStateWasWiped()) {
            invalidatePersistedCoarseState()
            return@synchronized
        }
        if (store.getActivePolygonIds().isEmpty()) {
            approachMonitor.stop(store.userStateGeneration())
        }
    }

    /** Processes fixes from the bounded session opened by a polygon or movement-trigger wake. */
    suspend fun processApproachLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long
    ): PolygonSamplingDecision {
        if (locations.isEmpty()) return PolygonSamplingDecision.CONTINUE
        val admitted = synchronized(controllerLock) {
            hasMatchingIdentifiedUserLocked() &&
                store.userStateGeneration() == expectedUserStateGeneration
        }
        if (!admitted) return PolygonSamplingDecision.STALE
        var lastAcceptedLocation: Location? = null
        for (location in locations.sortedBy(Location::getElapsedRealtimeNanos)) {
            val fix = location.toPolygonLocationFix() ?: continue
            val shouldProcess = synchronized(controllerLock) {
                if (!hasMatchingIdentifiedUserLocked() ||
                    store.userStateGeneration() != expectedUserStateGeneration
                ) {
                    return PolygonSamplingDecision.STALE
                }
                val routableIds = store.getRoutableRegisteredIds()
                val polygons = store.getCachedRegions().filter {
                    it.id in routableIds && it.isPolygon
                }
                polygons.filter {
                    it.distanceTo(location.latitude, location.longitude) +
                        fix.sample.horizontalAccuracyMeters <= it.radius
                }
                    .forEach { region ->
                        if (region.id !in store.getActivePolygonIds()) {
                            store.activatePolygon(region.id)
                            engine.activateFromApproach(region.id, fix.elapsedRealtimeNanos)
                        }
                    }
                store.getActivePolygonIds().isNotEmpty()
            }
            if (!shouldProcess) continue
            if (processTriggeredLocation(location, expectedUserStateGeneration)) {
                lastAcceptedLocation = location
            }
            synchronized(controllerLock) {
                if (!hasMatchingIdentifiedUserLocked() ||
                    store.userStateGeneration() != expectedUserStateGeneration
                ) {
                    return PolygonSamplingDecision.STALE
                }
                val coarseInside = store.getCoarseInsidePolygonIds()
                val committedInside = store.getEnteredIds()
                val approachOnly = store.getActivePolygonIds() - coarseInside - committedInside
                approachOnly.forEach { id ->
                    val region = store.getCachedRegion(id)
                    val confidentlyOutside = region?.isPolygon != true ||
                        region.distanceTo(location.latitude, location.longitude) -
                        fix.sample.horizontalAccuracyMeters >
                        region.radius + APPROACH_EXIT_HYSTERESIS_METERS
                    if (confidentlyOutside) {
                        store.deactivatePolygon(id)
                        engine.deactivate(id)
                    }
                }
            }
        }
        val sessionIsCurrent = synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                return@synchronized false
            }
            if (store.getActivePolygonIds().isEmpty()) engine.stop()
            true
        }
        if (!sessionIsCurrent) return PolygonSamplingDecision.STALE
        val safelyPassive = lastAcceptedLocation?.let {
            updateMovementTriggerFromAcceptedFix(it, expectedUserStateGeneration)
        } != null
        // Guarded like every other read of the active set. Unlocked, a reconcile or a deactivate
        // landing here flips the answer: CONTINUE with nothing active spends battery on nothing,
        // and STOP with a polygon still active loses its EXIT.
        //
        // Generation too, not just the lock. The trigger update above awaits GMS, and an identify
        // landing in that gap leaves this continuation reading the NEW user's active set. It would
        // answer CONTINUE, and the receiver would then start a session for the generation that has
        // already ended, replacing the approach request the new user just armed.
        return synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                PolygonSamplingDecision.STALE
            } else if (safelyPassive || store.getActivePolygonIds().isEmpty()) {
                PolygonSamplingDecision.STOP
            } else {
                PolygonSamplingDecision.CONTINUE
            }
        }
    }

    private companion object {
        const val APPROACH_EXIT_HYSTERESIS_METERS = 100.0
    }

    fun invalidatePersistedCoarseState() = synchronized(controllerLock) {
        val generation = store.userStateGeneration()
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop(generation)
    }

    fun invalidateOsRegistrationState() = synchronized(controllerLock) {
        val generation = store.userStateGeneration()
        store.saveRegisteredIds(emptySet())
        store.saveRoutableRegisteredIds(emptySet())
        store.saveRetainedRegisteredRegions(emptyList())
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop(generation)
    }

    fun stopAll() = synchronized(controllerLock) {
        val generation = store.userStateGeneration()
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop(generation)
    }

    fun clearUserScopedState() = synchronized(controllerLock) {
        // The generation/registration wipe shares this lock with coarse callbacks. A callback that
        // GMS queued before sign-out can therefore neither pass its generation check after the wipe
        // nor re-arm exact-location monitoring between the wipe and service teardown.
        val generation = store.userStateGeneration()
        store.clearUserScopedState()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop(generation)
    }

    fun clearUserSessionRetainingOsRegistrations() = synchronized(controllerLock) {
        val generation = store.userStateGeneration()
        store.clearUserSessionRetainingOsRegistrations()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop(generation)
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) = synchronized(controllerLock) {
        store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop(expectedUserStateGeneration)
    }

    fun beginUserSession(userId: String) = synchronized(controllerLock) {
        openSessionLocked { store.beginUserSession(userId) }
    }

    /**
     * For callers acting on an identity they did not establish: app launch, boot restore, an OS
     * callback. The read is handed to the store so it happens under the lock that opens the
     * session, instead of racing an identify to the write.
     */
    fun beginUserSessionForCurrentUser() = synchronized(controllerLock) {
        openSessionLocked { store.beginUserSessionForCurrentUser(secureUserStore::getUserId) }
    }

    private fun openSessionLocked(open: () -> Unit) {
        val previousGeneration = store.userStateGeneration()
        open()
        // The generation moves only when the session actually changed, so it says whether the
        // previous session's sampling and approach request are now orphaned.
        if (store.userStateGeneration() != previousGeneration) {
            engine.stop()
            approachMonitor.stop(previousGeneration)
            lastCoarseTransitionElapsedNanos.clear()
        }
    }

    fun publishRegistrationIfCurrent(
        expectedUserStateGeneration: Long,
        userId: String,
        publish: () -> Unit
    ): Boolean = synchronized(controllerLock) {
        if (secureUserStore.getUserId() != userId ||
            store.activeUserSessionId() != userId ||
            store.userStateGeneration() != expectedUserStateGeneration
        ) {
            return@synchronized false
        }
        publish()
        true
    }

    private fun isCurrentRegisteredPolygon(
        polygonId: String,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ): Boolean = synchronized(controllerLock) {
        isCurrentRegisteredPolygonLocked(polygonId, expectedUserStateGeneration, expectedRegionRevision)
    }

    private fun isCurrentRegisteredPolygonLocked(
        polygonId: String,
        expectedUserStateGeneration: Long,
        expectedRegionRevision: Int?
    ): Boolean {
        val currentRegion = store.getCachedRegion(polygonId)
        return hasMatchingIdentifiedUserLocked() &&
            store.userStateGeneration() == expectedUserStateGeneration &&
            polygonId in store.getRoutableRegisteredIds() &&
            currentRegion?.isPolygon == true &&
            (expectedRegionRevision == null || currentRegion.transitionRevision() == expectedRegionRevision)
    }

    private fun hasMatchingIdentifiedUserLocked(): Boolean {
        val currentUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() } ?: return false
        return store.activeUserSessionId() == currentUserId
    }

    private suspend fun processTriggeredLocation(
        location: Location,
        expectedUserStateGeneration: Long
    ): Boolean = engine.processResponsiveLocation(location, expectedUserStateGeneration)

    // Reached only from a delivered GMS geofence callback, which the OS makes only for a
    // registration that already held the permission.
    @SuppressLint("MissingPermission")
    private suspend fun updateMovementTriggerFromAcceptedFix(
        location: Location,
        expectedUserStateGeneration: Long
    ): Float? {
        val fix = location.toPolygonLocationFix() ?: return null
        val registeredPolygons = store.getRoutableRegisteredIds().mapNotNull { id ->
            store.getCachedRegion(id)?.takeIf(GeofenceRegion::isPolygon)
        }
        val normalRadius = store.getCachedConfigOrFallback().localRefreshTriggerRadius
        val safeRadius = movementTriggerPolicy.safeRadiusMeters(
            regions = registeredPolygons,
            committedInsideIds = store.getEnteredIds(),
            sample = fix.sample,
            normalRadiusMeters = normalRadius
        ) ?: return null
        val trigger = GeofenceRegion(
            id = GeofenceConstants.MOVEMENT_TRIGGER_ID,
            latitude = location.latitude,
            longitude = location.longitude,
            radius = safeRadius,
            transitionTypes = listOf(GeofenceTransitionType.EXIT)
        )
        val result = manager.replaceMovementTrigger(trigger)
        if (result.isFailure) return null
        // Generation-checked like the stop below it: the registration above awaited GMS with no
        // lock held, so a sign-out can have completed inside that gap and cleared this state.
        val recorded = store.saveLastMovementTriggerLocationIfCurrent(
            GeofenceLocation(location.latitude, location.longitude),
            safeRadius,
            expectedUserStateGeneration
        )
        if (!recorded) return null
        approachMonitor.stop(expectedUserStateGeneration)
        return safeRadius
    }

    private fun acceptCoarseTransition(polygonId: String, location: Location?): Boolean =
        synchronized(controllerLock) {
            val elapsedRealtimeNanos = location?.elapsedRealtimeNanos?.takeIf { it > 0L }
                ?: return@synchronized true
            val previous = lastCoarseTransitionElapsedNanos[polygonId]
            if (previous != null && elapsedRealtimeNanos <= previous) return@synchronized false
            lastCoarseTransitionElapsedNanos[polygonId] = elapsedRealtimeNanos
            true
        }

    private fun osStateWasWiped(): Boolean {
        val rebooted = store.getLastRegistrationUptime()?.let { SystemClock.elapsedRealtime() < it } == true
        val currentPackageUpdate = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrNull()
        val replaced = store.getLastRegistrationPackageUpdateTime()?.let { stamped ->
            currentPackageUpdate != null && currentPackageUpdate != stamped
        } == true
        return rebooted || replaced
    }
}

internal enum class PolygonSamplingDecision {
    CONTINUE,
    STOP,
    STALE
}
