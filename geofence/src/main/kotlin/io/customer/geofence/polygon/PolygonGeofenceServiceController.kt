package io.customer.geofence.polygon

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.customer.geofence.GeofenceLogger
import io.customer.geofence.PolygonTrackingMode
import io.customer.geofence.distanceTo
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.transitionRevision
import io.customer.sdk.data.store.SecureUserStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persists coarse-circle activity, then drives whichever acquisition the configured
 * [PolygonTrackingMode] allows.
 *
 * Both modes share one path: coarse triggers and passive approach batches are evaluated by
 * [PolygonLocationEngine] on the responsive, single-decisive-fix policy. [PolygonTrackingMode
 * .CONTINUOUS] adds — never replaces — a location foreground service whose
 * [PolygonFineLocationStream] contributes multi-fix evidence while the device is inside a trigger
 * circle. Every way that service can fail (host configuration, an OS background-start restriction,
 * a refused promotion, a revoked permission) ends in the same place: the stream stops and the
 * responsive path carries on alone.
 */
internal class PolygonGeofenceServiceController(
    private val context: Context,
    private val store: GeofenceRegionStore,
    private val engine: PolygonLocationEngine,
    private val fineStream: PolygonFineLocationStream,
    private val approachMonitor: PolygonApproachMonitor,
    private val secureUserStore: SecureUserStore,
    private val logger: GeofenceLogger,
    private val continuousModeValidator: PolygonContinuousModeValidator =
        PolygonContinuousModeValidator(context)
) {
    private val controllerLock = Any()
    private val coarseTransitionMutex = Mutex()
    private val lastCoarseTransitionElapsedNanos = mutableMapOf<String, Long>()
    private val servicePromotionHandler = Handler(Looper.getMainLooper())
    private val servicePromotionWatchdogs = mutableMapOf<Long, Runnable>()
    private var serviceStartGeneration = 0L
    private var pendingServicePromotionGeneration: Long? = null
    private var servicePromotionRetryCount = 0
    private var promotionDeniedUntilElapsedMs: Long? = null

    /**
     * The highest start generation that no longer owns the runtime, because a newer start replaced
     * it or [stopService] cancelled it. Service callbacks are asynchronous and are raised from the
     * service main thread and from [startService]'s own caller thread, so one can land long after
     * its generation stopped being the reason a stream is running.
     */
    private var supersededServiceGeneration = 0L

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
        startServiceIfContinuous()
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
                startServiceIfContinuous()
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
        val polygonIds = store.getRoutableRegisteredIds().filterTo(mutableSetOf()) {
            store.getCachedRegion(it)?.isPolygon == true
        }
        if (polygonIds.isEmpty()) approachMonitor.stop() else approachMonitor.start(store.userStateGeneration())
        // Foreground entry is exactly when a background-start restriction stops applying, so a
        // previous denial must not keep the gate shut through the one window that would succeed.
        promotionDeniedUntilElapsedMs = null
        if (store.getActivePolygonIds().isNotEmpty()) startServiceIfContinuous()
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
            if (store.getActivePolygonIds().isEmpty()) stopService() else startServiceIfContinuous()
            true
        }
    }

    private companion object {
        const val APPROACH_EXIT_HYSTERESIS_METERS = 100.0
        const val SERVICE_PROMOTION_TIMEOUT_MS = 10_000L
        const val MAXIMUM_SERVICE_PROMOTION_RETRIES = 1
        const val PROMOTION_DENIED_BACKOFF_MS = 300_000L
    }

    private enum class PromotionTimeoutAction {
        NONE,
        RETRY,
        STOP
    }

    /**
     * Registers the fine stream for a service that has already promoted itself, or returns `null`
     * when nothing should be sampled — which the caller answers by stopping the service. Never
     * called before [startForeground][android.app.Service.startForeground] succeeds, so a stream
     * can only exist behind a live location foreground service.
     */
    fun startFineLocationStream(onUnavailable: () -> Unit): Long? = synchronized(controllerLock) {
        if (!isContinuousTrackingEnabledLocked()) {
            fineStream.stop()
            return@synchronized null
        }
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
            fineStream.stop()
            lastCoarseTransitionElapsedNanos.clear()
            return@synchronized null
        }
        fineStream.start(onUnavailable)
    }

    fun isContinuousTrackingEnabled(): Boolean = synchronized(controllerLock) {
        isContinuousTrackingEnabledLocked()
    }

    /**
     * Persists the configured mode and reconciles the runtime to it. Does blocking preference,
     * Keystore and binder work, so hosts must call it off the main thread.
     */
    fun setTrackingMode(mode: PolygonTrackingMode) = synchronized(controllerLock) {
        if (mode == PolygonTrackingMode.CONTINUOUS) {
            continuousModeValidator.configurationError()?.let(logger::logPolygonMonitoringFailed)
        }
        store.savePolygonTrackingMode(mode)
        if (mode == PolygonTrackingMode.RESPONSIVE) {
            // Multi-fix evidence gathered under the previous mode has no owner once the stream is
            // gone, so it is discarded rather than left to confirm from a sparser source.
            engine.stop()
            promotionDeniedUntilElapsedMs = null
            stopService()
        } else if (hasMatchingIdentifiedUserLocked() && store.getActivePolygonIds().isNotEmpty()) {
            // Unconditional rather than only on a mode change: the mode is now applied
            // asynchronously, so a process that starts already configured for CONTINUOUS has to
            // reach a running service from here too.
            startService()
        }
    }

    /** Acknowledges a promotion the service has already completed. */
    fun onServicePromoted(serviceGeneration: Long) = synchronized(controllerLock) {
        // Deliberately not generation-gated: this callback is only raised after startForeground has
        // returned, so even a superseded generation proves that starting is allowed right now and
        // that an earlier denial no longer describes the device. Only the latch, which is about one
        // specific start, is matched below.
        promotionDeniedUntilElapsedMs = null
        if (pendingServicePromotionGeneration == serviceGeneration) {
            clearServicePromotionWatchdogLocked(serviceGeneration)
            pendingServicePromotionGeneration = null
            servicePromotionRetryCount = 0
        }
    }

    /**
     * The service reached no usable state — an OS background-start restriction, a refused
     * `startForeground`, or a location permission that is no longer granted. Releases the latch
     * without ever marking the generation promoted, and holds further attempts off for a bounded
     * window (reopened by [recover] on foreground entry, when a background-start restriction stops
     * applying) so a denial is not re-requested on every approach batch. Responsive evaluation,
     * including approach monitoring, is untouched.
     *
     * A `null` generation is a sticky restart, whose intent carries no generation: it can only
     * describe whatever service is running now, so it always fails closed.
     */
    fun onServicePromotionFailed(serviceGeneration: Long?, reason: String?) = synchronized(controllerLock) {
        logger.logPolygonMonitoringFailed(reason)
        // A failure that a newer start or a deliberate stop has already replaced describes nothing
        // that is running: acting on it would stop the newer generation's stream and hold its
        // restart behind a backoff it never earned. Whatever superseded it reports its own outcome,
        // and a generation that stays silent is still caught by its promotion watchdog.
        if (serviceGeneration != null && serviceGeneration <= supersededServiceGeneration) {
            return@synchronized
        }
        promotionDeniedUntilElapsedMs = SystemClock.elapsedRealtime() + PROMOTION_DENIED_BACKOFF_MS
        fineStream.stop()
        if (serviceGeneration == null || pendingServicePromotionGeneration == serviceGeneration) {
            pendingServicePromotionGeneration?.let(::clearServicePromotionWatchdogLocked)
            pendingServicePromotionGeneration = null
            servicePromotionRetryCount = 0
        }
    }

    fun onServiceDestroyed(serviceGeneration: Long?) = synchronized(controllerLock) {
        if (serviceGeneration != null && pendingServicePromotionGeneration == serviceGeneration) {
            clearServicePromotionWatchdogLocked(serviceGeneration)
            pendingServicePromotionGeneration = null
        }
    }

    fun invalidatePersistedCoarseState() = synchronized(controllerLock) {
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
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
        approachMonitor.stop()
        stopService()
    }

    fun stopAll() = synchronized(controllerLock) {
        store.clearActivePolygonIds()
        store.retainCoarseInsidePolygonIds(emptySet())
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
        stopService()
    }

    fun clearUserScopedState() = synchronized(controllerLock) {
        // The generation/registration wipe shares this lock with coarse callbacks. A callback that
        // GMS queued before sign-out can therefore neither pass its generation check after the wipe
        // nor re-arm exact-location monitoring between the wipe and service teardown.
        store.clearUserScopedState()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
        stopService()
    }

    fun clearUserSessionRetainingOsRegistrations() = synchronized(controllerLock) {
        store.clearUserSessionRetainingOsRegistrations()
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
        stopService()
    }

    fun completeUserReset(
        expectedUserStateGeneration: Long,
        osRegistrationsCleared: Boolean
    ) = synchronized(controllerLock) {
        store.completeUserReset(expectedUserStateGeneration, osRegistrationsCleared)
        engine.stop()
        lastCoarseTransitionElapsedNanos.clear()
        approachMonitor.stop()
        stopService()
    }

    fun beginUserSession(userId: String) = synchronized(controllerLock) {
        val changed = store.activeUserSessionId() != userId
        store.beginUserSession(userId)
        if (changed) {
            engine.stop()
            approachMonitor.stop()
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

    /**
     * Coarse triggers and passive approach batches are single, widely spaced fixes whichever mode
     * is configured, so they are always judged on the decisive-single-fix policy. Routing them
     * through the multi-fix policy because CONTINUOUS is selected would make the opt-in *lose*
     * transitions that responsive mode commits — the fine stream, not the mode flag, is what earns
     * multi-fix evidence.
     */
    private suspend fun processTriggeredLocation(
        location: Location,
        expectedUserStateGeneration: Long
    ) {
        engine.processResponsiveLocation(location, expectedUserStateGeneration)
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
        continuousModeValidator.configurationError()?.let {
            logger.logPolygonMonitoringFailed(it)
            return
        }
        val generation = synchronized(controllerLock) {
            val deniedUntil = promotionDeniedUntilElapsedMs
            when {
                !isContinuousTrackingEnabledLocked() -> null
                pendingServicePromotionGeneration != null -> null
                deniedUntil != null && SystemClock.elapsedRealtime() < deniedUntil -> null
                else -> {
                    supersededServiceGeneration = serviceStartGeneration
                    serviceStartGeneration += 1L
                    serviceStartGeneration.also { pendingServicePromotionGeneration = it }
                }
            }
        }
        if (generation == null) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PolygonLocationService::class.java).putExtra(
                    PolygonLocationService.EXTRA_SERVICE_START_GENERATION,
                    generation
                )
            )
            val watchdog = Runnable { onServicePromotionTimedOut(generation) }
            synchronized(controllerLock) {
                if (pendingServicePromotionGeneration == generation) {
                    servicePromotionWatchdogs[generation] = watchdog
                    servicePromotionHandler.postDelayed(watchdog, SERVICE_PROMOTION_TIMEOUT_MS)
                }
            }
        } catch (e: RuntimeException) {
            // Android 12+ refuses background foreground-service starts outright. Nothing was
            // promoted, so release the latch, back off, and leave the responsive path running.
            onServicePromotionFailed(generation, e.message)
        }
    }

    /**
     * Stops the service and the fine stream, including while a promotion is still pending.
     *
     * The pending case used to be skipped, to avoid stopping a service before it could promote
     * itself. That left fine sampling alive through sign-out, an identity switch and catalog
     * removal, which is the worse failure by far. [PolygonLocationService] promotes before it
     * evaluates anything and stops itself when the state it finds is no longer worth sampling, so a
     * cancelled start ends as a foreground service that lives for a few milliseconds.
     */
    private fun stopService() {
        synchronized(controllerLock) {
            pendingServicePromotionGeneration?.let(::clearServicePromotionWatchdogLocked)
            pendingServicePromotionGeneration = null
            servicePromotionRetryCount = 0
            // Every start issued so far is cancelled here, so a failure any of them reports late
            // must not back off the next start this teardown is making room for.
            supersededServiceGeneration = serviceStartGeneration
        }
        fineStream.stop()
        try {
            context.stopService(Intent(context, PolygonLocationService::class.java))
        } catch (e: RuntimeException) {
            logger.logPolygonMonitoringFailed(e.message)
        }
    }

    /**
     * The service never reported a successful promotion. Reopens the start gate so a later coarse
     * trigger is not permanently blocked by one silent start, retries once, and otherwise leaves
     * the responsive path to carry the session.
     */
    private fun onServicePromotionTimedOut(generation: Long) {
        val action = synchronized(controllerLock) {
            // A watchdog for a generation that has already been promoted, destroyed or torn down
            // has nothing to say about the current state.
            if (pendingServicePromotionGeneration != generation) return
            clearServicePromotionWatchdogLocked(generation)
            pendingServicePromotionGeneration = null
            val canRetry = servicePromotionRetryCount < MAXIMUM_SERVICE_PROMOTION_RETRIES &&
                isContinuousTrackingEnabledLocked() &&
                hasMatchingIdentifiedUserLocked() &&
                store.getActivePolygonIds().isNotEmpty()
            if (canRetry) servicePromotionRetryCount += 1
            when {
                canRetry -> PromotionTimeoutAction.RETRY
                store.getActivePolygonIds().isEmpty() -> PromotionTimeoutAction.STOP
                else -> PromotionTimeoutAction.NONE
            }
        }
        logger.logPolygonMonitoringFailed("the foreground service did not acknowledge promotion")
        when (action) {
            PromotionTimeoutAction.RETRY -> startService()
            PromotionTimeoutAction.STOP -> stopService()
            PromotionTimeoutAction.NONE -> Unit
        }
    }

    private fun clearServicePromotionWatchdogLocked(generation: Long) {
        servicePromotionWatchdogs.remove(generation)?.let(servicePromotionHandler::removeCallbacks)
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

    private fun startServiceIfContinuous() {
        if (isContinuousTrackingEnabledLocked()) startService()
    }

    private fun isContinuousTrackingEnabledLocked(): Boolean =
        store.getPolygonTrackingMode() == PolygonTrackingMode.CONTINUOUS
}
