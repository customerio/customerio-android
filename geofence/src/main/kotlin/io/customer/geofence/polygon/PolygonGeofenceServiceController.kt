package io.customer.geofence.polygon

import android.content.Context
import android.location.Location
import android.os.SystemClock
import io.customer.geofence.distanceTo
import io.customer.geofence.store.GeofenceRegionStore
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
    private val secureUserStore: SecureUserStore
) {
    private val controllerLock = Any()
    private val coarseTransitionMutex = Mutex()
    private val lastCoarseTransitionElapsedNanos = mutableMapOf<String, Long>()

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
            processTriggeredLocation(triggeringLocation, expectedUserStateGeneration)
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
    }

    fun deactivate(polygonId: String) = synchronized(controllerLock) {
        store.recordPolygonCoarseOutside(polygonId)
        store.deactivatePolygon(polygonId)
        engine.deactivate(polygonId)
        if (store.getActivePolygonIds().isEmpty()) engine.stop()
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
            processTriggeredLocation(triggeringLocation, expectedUserStateGeneration)
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
            } else {
                deactivate(polygonId)
            }
        }
    }

    fun resetEvidence(polygonId: String) = synchronized(controllerLock) {
        engine.resetEvidence(polygonId)
    }

    fun reconcileRegisteredPolygons(ids: Set<String>) = synchronized(controllerLock) {
        val removed = store.getActivePolygonIds() - ids
        store.retainActivePolygonIds(ids)
        store.retainCoarseInsidePolygonIds(ids)
        removed.forEach(engine::deactivate)
        lastCoarseTransitionElapsedNanos.keys.retainAll(ids)
        if (ids.isEmpty() || !hasMatchingIdentifiedUserLocked()) {
            approachMonitor.stop()
        } else {
            approachMonitor.start(store.userStateGeneration())
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
        val polygonIds = store.getRoutableRegisteredIds().filterTo(mutableSetOf()) {
            store.getCachedRegion(it)?.isPolygon == true
        }
        if (polygonIds.isEmpty()) approachMonitor.stop() else approachMonitor.start(store.userStateGeneration())
    }

    /**
     * Uses passive background fixes to enter the existing fine evaluator before a delayed GMS
     * enclosing-circle callback arrives. These activations deliberately do not claim the OS circle
     * is inside; a later fix outside the trigger can therefore retire them without waiting for GMS.
     */
    suspend fun processApproachLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long
    ): Boolean {
        if (locations.isEmpty()) return true
        val admitted = synchronized(controllerLock) {
            hasMatchingIdentifiedUserLocked() &&
                store.userStateGeneration() == expectedUserStateGeneration
        }
        if (!admitted) return false
        for (location in locations.sortedBy(Location::getElapsedRealtimeNanos)) {
            val fix = location.toPolygonLocationFix() ?: continue
            val shouldProcess = synchronized(controllerLock) {
                if (!hasMatchingIdentifiedUserLocked() ||
                    store.userStateGeneration() != expectedUserStateGeneration
                ) {
                    return false
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
            processTriggeredLocation(location, expectedUserStateGeneration)
            synchronized(controllerLock) {
                if (!hasMatchingIdentifiedUserLocked() ||
                    store.userStateGeneration() != expectedUserStateGeneration
                ) {
                    return false
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
        return synchronized(controllerLock) {
            if (!hasMatchingIdentifiedUserLocked() ||
                store.userStateGeneration() != expectedUserStateGeneration
            ) {
                return@synchronized false
            }
            if (store.getActivePolygonIds().isEmpty()) engine.stop()
            true
        }
    }

    private companion object {
        const val APPROACH_EXIT_HYSTERESIS_METERS = 100.0
    }

    fun invalidatePersistedCoarseState() = synchronized(controllerLock) {
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
    }

    fun invalidateOsRegistrationState() = synchronized(controllerLock) {
        store.saveRegisteredIds(emptySet())
        store.saveRoutableRegisteredIds(emptySet())
        store.saveRetainedRegisteredRegions(emptyList())
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
    }

    fun stopAll() = synchronized(controllerLock) {
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
    }

    fun clearUserScopedState() = synchronized(controllerLock) {
        // The generation/registration wipe shares this lock with coarse callbacks. A callback that
        // GMS queued before sign-out can therefore neither pass its generation check after the wipe
        // nor re-arm exact-location monitoring between the wipe and service teardown.
        store.clearUserScopedState()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
    }

    fun clearUserSessionRetainingOsRegistrations() = synchronized(controllerLock) {
        store.clearUserSessionRetainingOsRegistrations()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) = synchronized(controllerLock) {
        store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
    }

    fun beginUserSession(userId: String) = synchronized(controllerLock) {
        val changed = store.activeUserSessionId() != userId
        store.beginUserSession(userId)
        if (changed) {
            engine.stop()
            approachMonitor.stop()
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
    ) = engine.processResponsiveLocation(location, expectedUserStateGeneration)

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
