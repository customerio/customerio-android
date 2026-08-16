package io.customer.geofence.polygon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists coarse-circle activity before controlling the process-lifetime location service. */
internal class PolygonGeofenceServiceController(
    private val context: Context,
    private val store: GeofenceRegionStore,
    private val engine: PolygonLocationEngine,
    private val secureUserStore: SecureUserStore,
    private val logger: GeofenceLogger
) {
    private val serviceIntent = Intent(context, PolygonLocationService::class.java)
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
            engine.processLocation(triggeringLocation, expectedUserStateGeneration)
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
        startService()
    }

    fun deactivate(polygonId: String) = synchronized(controllerLock) {
        store.recordPolygonCoarseOutside(polygonId)
        store.deactivatePolygon(polygonId)
        engine.deactivate(polygonId)
        if (store.getActivePolygonIds().isEmpty()) stopService()
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
            engine.processLocation(triggeringLocation, expectedUserStateGeneration)
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
                startService()
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
        if (store.getActivePolygonIds().isEmpty()) stopService()
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
        if (store.getActivePolygonIds().isNotEmpty()) startService()
    }

    @SuppressLint("MissingPermission")
    fun startEngineForService(onUnavailable: () -> Unit): Boolean = synchronized(controllerLock) {
        val identifiedUserId = secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        if (identifiedUserId != null && store.activeUserSessionId() == null) {
            store.beginUserSession(identifiedUserId)
        }
        if (!hasMatchingIdentifiedUserLocked() ||
            store.getRoutableRegisteredIds().isEmpty() ||
            store.getActivePolygonIds().isEmpty()
        ) {
            store.clearActivePolygonIds()
            store.retainCoarseInsidePolygonIds(emptySet())
            engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            return@synchronized false
        }
        engine.start(onUnavailable)
        true
    }

    fun invalidatePersistedCoarseState() = synchronized(controllerLock) {
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        stopService()
    }

    fun invalidateOsRegistrationState() = synchronized(controllerLock) {
        store.saveRegisteredIds(emptySet())
        store.saveRoutableRegisteredIds(emptySet())
        store.saveRetainedRegisteredRegions(emptyList())
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        stopService()
    }

    fun stopAll() = synchronized(controllerLock) {
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        stopService()
    }

    fun clearUserScopedState() = synchronized(controllerLock) {
        // The generation/registration wipe shares this lock with coarse callbacks. A callback that
        // GMS queued before sign-out can therefore neither pass its generation check after the wipe
        // nor re-arm exact-location monitoring between the wipe and service teardown.
        store.clearUserScopedState()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        stopService()
    }

    fun clearUserSessionRetainingOsRegistrations() = synchronized(controllerLock) {
        store.clearUserSessionRetainingOsRegistrations()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        stopService()
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) = synchronized(controllerLock) {
        store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        stopService()
    }

    fun beginUserSession(userId: String) = synchronized(controllerLock) {
        val changed = store.activeUserSessionId() != userId
        store.beginUserSession(userId)
        if (changed) {
            engine.stop()
            lastCoarseTransitionElapsedNanos.clear()
            stopService()
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

    private fun acceptCoarseTransition(polygonId: String, location: Location?): Boolean =
        synchronized(controllerLock) {
            val elapsedRealtimeNanos = location?.elapsedRealtimeNanos?.takeIf { it > 0L }
                ?: return@synchronized true
            val previous = lastCoarseTransitionElapsedNanos[polygonId]
            if (previous != null && elapsedRealtimeNanos <= previous) return@synchronized false
            lastCoarseTransitionElapsedNanos[polygonId] = elapsedRealtimeNanos
            true
        }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(
                context,
                serviceIntent
            )
        } catch (e: RuntimeException) {
            logger.logPolygonMonitoringFailed(e.message)
        }
    }

    private fun stopService() {
        context.stopService(serviceIntent)
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
