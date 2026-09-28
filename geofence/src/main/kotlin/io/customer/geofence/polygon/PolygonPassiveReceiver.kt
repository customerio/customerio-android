package io.customer.geofence.polygon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationResult
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.PolygonPassiveSkip
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.di.polygonPassiveMonitor
import io.customer.geofence.distanceTo
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Receives passive location fixes (ones other apps requested) for polygon monitoring.
 *
 * A passive fix is only an extra trigger: it goes through the same path as a GMS geofence callback,
 * so dedupe, accuracy decisions and emission are unchanged.
 */
class PolygonPassiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = runCatching { LocationResult.extractResult(intent) }.getOrNull() ?: return
        val fix = newestFix(result.locations) ?: return

        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            SDKComponent.scopeProvider.geofenceScope.launch {
                try {
                    handleFix(fix)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SDKComponent.geofenceLogger.logPolygonPassiveFailed(e.message)
                } finally {
                    runCatching { pendingResult.finish() }
                }
            }
        } catch (e: Exception) {
            SDKComponent.geofenceLogger.logPolygonPassiveFailed(e.message)
            runCatching { pendingResult.finish() }
        }
    }

    /**
     * A batch can carry several fixes. The older ones describe where the device was on the way in,
     * and each would buy another evaluation of the same arrival, so only the freshest is used.
     */
    internal fun newestFix(locations: List<Location>): Location? =
        locations.maxByOrNull(Location::getElapsedRealtimeNanos)

    internal suspend fun handleFix(fix: Location) {
        val logger = SDKComponent.geofenceLogger
        val store = SDKComponent.android().geofenceRegionStore
        val routableIds = store.getRoutableRegisteredIds()
        val polygons = store.getCachedRegions().filter { it.id in routableIds && it.isPolygon }
        if (polygons.isEmpty()) {
            // Retires itself, like the re-check worker, so a request left live with GMS costs one
            // wake rather than every other app's fix waking this process indefinitely. Race: a
            // delivery just before a new session's reconcile can leave the listener off until the
            // next registration change.
            logger.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED)
            SDKComponent.android().polygonPassiveMonitor.stop()
            return
        }
        // Read before anything is dispatched, so a user change mid-dispatch is refused by the
        // controller rather than attributed to whoever is current when it lands.
        val expectedUserStateGeneration = store.userStateGeneration()
        // Read on entry, before the isArmed() check. That check refuses any delivery after
        // teardown's cancel; this token covers one admitted just before it, whose activation would
        // otherwise run after teardown's wipe and re-arm what was just removed.
        val expectedTeardownGeneration =
            SDKComponent.android().polygonGeofenceServiceController.teardownGeneration()
        // Teardown keeps routable ids and the user generation, so activate()'s own checks cannot
        // refuse a fix the OS dispatched before it; the cancelled registration is what changed.
        if (!SDKComponent.android().polygonPassiveMonitor.isArmed()) {
            logger.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED)
            return
        }
        val admitted = polygons.filter { it.distanceTo(fix.latitude, fix.longitude) <= it.radius }
        // The departure half, which makes activating from here safe: activate() records the polygon
        // coarse-inside and only a coarse EXIT clears it, so a polygon activated on an ENTER GMS
        // never issued could otherwise stay active indefinitely. Accuracy is subtracted rather than
        // gated on the arrival ceiling, which a passive fix would rarely meet.
        val activeIds = store.getActivePolygonIds()
        val departed = polygons.filter { region ->
            region.id in activeIds &&
                // Location.accuracy is 0 when unset, which would turn the margin off.
                fix.hasAccuracy() &&
                region.distanceTo(fix.latitude, fix.longitude) - fix.accuracy > region.radius
        }
        logger.logPolygonPassiveReceived(
            location = fix,
            candidateCount = polygons.size,
            admittedIds = admitted.map(GeofenceRegion::id).sorted(),
            clearedIds = departed.map(GeofenceRegion::id).sorted()
        )
        if (admitted.isEmpty() && departed.isEmpty()) return
        val controller = SDKComponent.android().polygonGeofenceServiceController
        // onCoarseExit, not the store flag and not a direct deactivate: it keeps a committed
        // arrival active, drops a duplicate delivery, and reports the holds it discarded.
        departed.forEach { region ->
            controller.onCoarseExit(
                polygonId = region.id,
                triggeringLocation = fix,
                expectedUserStateGeneration = expectedUserStateGeneration,
                expectedTeardownGeneration = expectedTeardownGeneration
            )
        }
        admitted.forEach { region ->
            controller.activate(
                polygonId = region.id,
                triggeringLocation = fix,
                expectedUserStateGeneration = expectedUserStateGeneration,
                expectedTeardownGeneration = expectedTeardownGeneration
            )
        }
    }
}
