package io.customer.geofence.polygon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import com.google.android.gms.location.LocationResult
import io.customer.geofence.DwellDiagnosticEvent
import io.customer.geofence.DwellDiagnosticRecord
import io.customer.geofence.GeofenceRegion
import io.customer.geofence.PolygonPassiveSkip
import io.customer.geofence.di.dwellDiagnosticRecorder
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.geofence.di.polygonPassiveMonitor
import io.customer.geofence.distanceTo
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Receives passively observed location fixes for polygon monitoring. GMS does not name the request
 * that computed one, which may be this SDK's own. They take the same path as a GMS geofence
 * callback, so dedupe, accuracy decisions and emission are unchanged.
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

    /** Older fixes in a batch would each buy another evaluation of the same arrival. */
    internal fun newestFix(locations: List<Location>): Location? =
        locations.maxByOrNull(Location::getElapsedRealtimeNanos)

    /** Observational only: a diagnostics failure must not change how the fix is handled. */
    private fun recordWake(fix: Location) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val record = DwellDiagnosticRecord(
            event = DwellDiagnosticEvent.WAKE_PASSIVE_FIX,
            atElapsedMs = nowElapsedMs,
            returnedAccuracyMeters = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
            fixAgeMs = fix.elapsedRealtimeNanos.takeIf { it > 0L }
                ?.let { nowElapsedMs - TimeUnit.NANOSECONDS.toMillis(it) }
                ?.takeIf { it >= 0L }
        )
        runCatching {
            val recorder = SDKComponent.android().dwellDiagnosticRecorder
            recorder.record(record)
        }
    }

    internal suspend fun handleFix(fix: Location) {
        // First, before any read or await: one record per delivery, bound to the owner it arrived under.
        recordWake(fix)
        val logger = SDKComponent.geofenceLogger
        val store = SDKComponent.android().geofenceRegionStore
        val routableIds = store.getRoutableRegisteredIds()
        val polygons = store.getCachedRegions().filter { it.id in routableIds && it.isPolygon }
        if (polygons.isEmpty()) {
            // Retires itself so a stale GMS request costs one wake, not one per other app's fix. A
            // delivery just before a new session's reconcile can leave it off until the next change.
            logger.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED)
            SDKComponent.android().polygonPassiveMonitor.stop()
            return
        }
        // Read before dispatch, so a user change mid-dispatch is refused rather than misattributed.
        val expectedUserStateGeneration = store.userStateGeneration()
        // Read before isArmed(), which refuses deliveries after teardown's cancel; this token covers
        // one admitted just before it, which would otherwise re-arm what teardown just removed.
        val expectedTeardownGeneration =
            SDKComponent.android().polygonGeofenceServiceController.teardownGeneration()
        // Teardown keeps routable ids and the user generation, so only the cancelled registration
        // refuses a fix the OS dispatched before it.
        if (!SDKComponent.android().polygonPassiveMonitor.isArmed()) {
            logger.logPolygonPassiveSkipped(PolygonPassiveSkip.NOTHING_REGISTERED)
            return
        }
        val admitted = polygons.filter { it.distanceTo(fix.latitude, fix.longitude) <= it.radius }
        // Makes activating here safe: only a coarse EXIT clears activate()'s coarse-inside. Accuracy
        // is subtracted, not gated on the arrival ceiling, which a passive fix rarely meets.
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
