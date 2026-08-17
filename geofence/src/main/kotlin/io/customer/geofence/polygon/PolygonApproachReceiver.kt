package io.customer.geofence.polygon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationResult
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.polygonApproachMonitor
import io.customer.geofence.di.polygonApproachWorkScheduler
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives the low-power approach fixes delivered by Google Play services. */
class PolygonApproachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = runCatching { LocationResult.extractResult(intent) }.getOrNull() ?: return
        if (result.locations.isEmpty()) return
        val expectedUserStateGeneration = intent.getLongExtra(
            PolygonApproachMonitor.EXTRA_USER_STATE_GENERATION,
            Long.MIN_VALUE
        )
        if (expectedUserStateGeneration == Long.MIN_VALUE) return

        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            val workScope = SDKComponent.scopeProvider.geofenceScope
            workScope.launch {
                try {
                    val scheduled = SDKComponent.android().polygonApproachWorkScheduler.enqueue(
                        result.locations,
                        expectedUserStateGeneration
                    )
                    if (!scheduled) {
                        handleLocations(result.locations, expectedUserStateGeneration)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SDKComponent.geofenceLogger.logPolygonApproachMonitoringFailed(e.message)
                } finally {
                    pendingResult.finish()
                    workScope.cancel()
                }
            }
        } catch (e: Throwable) {
            SDKComponent.geofenceLogger.logPolygonApproachMonitoringFailed(e.message)
            pendingResult.finish()
        }
    }

    internal suspend fun handleLocations(
        locations: List<Location>,
        expectedUserStateGeneration: Long,
        userId: String? = SDKComponent.android().secureUserStore.getUserId(),
        controller: PolygonGeofenceServiceController =
            SDKComponent.android().polygonGeofenceServiceController,
        monitor: PolygonApproachMonitor = SDKComponent.android().polygonApproachMonitor
    ) {
        val identifiedUserId = userId?.takeIf { it.isNotEmpty() }
        if (identifiedUserId != null) {
            controller.beginUserSession(identifiedUserId)
            val accepted = controller.processApproachLocations(
                locations,
                expectedUserStateGeneration
            )
            if (accepted) {
                // A PendingIntent can cold-start a fresh SDK process. Adopt/re-register the live
                // request so a later sign-out can remove it immediately in this process.
                monitor.start(expectedUserStateGeneration)
                return
            }
        }
        monitor.removeStaleGeneration(expectedUserStateGeneration)
    }
}
