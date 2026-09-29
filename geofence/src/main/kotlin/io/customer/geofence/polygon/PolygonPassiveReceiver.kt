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
 * Receives fixes another app paid for and feeds the useful ones through the ordinary path.
 *
 * Same shape as [PolygonRecheckWorker] on purpose: admit the polygons whose wake circle contains
 * the fix, then hand each to the [PolygonGeofenceServiceController.activate] a GMS callback uses.
 * A passive fix is an extra trigger, never a second set of rules, so dedupe, the accuracy decision,
 * the on-demand precise fix and emission all stay where they already are.
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
            // Retires itself, the way the re-check worker cancels its own work. reconcile is the
            // only caller of stop(), and it runs from the registration paths alone, so a sign-out
            // or the kill switch leaves this request live with GMS across process death — and for
            // a signed-out user no later registration ever arrives to tear it down. Every other
            // app's fix would then wake our process to read the store and return, indefinitely.
            // Self-healing here bounds that at one wake rather than relying on every reset path
            // remembering.
            //
            // One narrow race this creates: a delivery between a session opening and that sync's
            // reconcile stops the listener asynchronously, and if GMS executes the removal after
            // reconcile's request, the listener is off until the next registration change. Bounded
            // by the next sync that has additions, and cheaper than the unbounded wake it replaces.
            logger.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED)
            SDKComponent.android().polygonPassiveMonitor.stop()
            return
        }
        // Read before anything is dispatched, so a user change mid-dispatch is refused by the
        // controller rather than attributed to whoever is current when it lands.
        val expectedUserStateGeneration = store.userStateGeneration()
        // Captured before the registration check below, because the two cover different windows
        // and neither covers the other.
        //
        // isArmed() is the outer gate. Teardown cancels the registration before it wipes any
        // state, so once that cancel lands the gate is shut synchronously and for good, and every
        // later delivery is refused here whatever the token says, including one the OS had already
        // dispatched. That is the case no in-process token can see.
        //
        // The token covers what gets past it: a delivery admitted a moment before the cancel,
        // whose activation then runs after teardown has wiped the state. Its token was read before
        // teardown bumped the generation, so the activation is refused at the write rather than
        // re-arming what was just removed. Read on entry for that reason, never at dispatch, or it
        // would be the post-teardown value and agree with whatever is current.
        val expectedTeardownGeneration =
            SDKComponent.android().polygonGeofenceServiceController.teardownGeneration()
        // A fix the OS dispatched before teardown completed still arrives afterwards, and
        // teardown leaves the routable ids and the user generation in place on purpose, so neither
        // of the checks activate() already makes can refuse it. The registration itself is the one
        // thing that did change: stop() removes the request and cancels the intent, so a delivery
        // under a dead registration is exactly the case to drop. Checked here rather than captured
        // earlier, because by the time this receiver runs any teardown has already happened.
        if (!SDKComponent.android().polygonPassiveMonitor.isArmed()) {
            logger.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED)
            return
        }
        val admitted = polygons.filter { it.distanceTo(fix.latitude, fix.longitude) <= it.radius }
        // The departure half, and the reason activating from here is safe. activate() records the
        // polygon coarse-inside and only a GMS coarse EXIT clears it, so a polygon this listener
        // activated from an ENTER GMS never issued could stay active for the life of the install.
        //
        // The accuracy is subtracted rather than gated on the arrival ceiling: that ceiling is for
        // ring-level decisions where the margin is the venue's own depth, while here the margin is
        // the whole accuracy value plus the circle's slack over the ring. A passive fix is whatever
        // another app asked for, so requiring the ceiling would leave this unreachable.
        val activeIds = store.getActivePolygonIds()
        val departed = polygons.filter { region ->
            region.id in activeIds &&
                // Location.accuracy reports 0 when unset, which would turn the margin off and
                // clear on a fix with unknown error. GMS fixes carry it; this keeps "decisively"
                // true of the predicate rather than of the provider.
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
