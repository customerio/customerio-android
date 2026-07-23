package io.customer.location

import io.customer.location.provider.LocationProvider
import io.customer.location.provider.LocationRequestException
import io.customer.location.type.LocationGranularity
import io.customer.sdk.core.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Coordinates location requests: one-shot only.
 * Uses an injected [LocationProvider] for platform location access.
 */
internal class LocationOrchestrator(
    private val config: LocationModuleConfig,
    private val logger: Logger,
    private val locationTracker: LocationTracker,
    private val locationProvider: LocationProvider
) {

    suspend fun requestLocation(intent: LocationRequestIntent) {
        // A request that starts tracked respects the OFF gate; silent ones fetch regardless —
        // geofencing needs a fix even when location tracking is OFF.
        if (intent.isTracked && !config.isEnabled) {
            logger.debug("Location tracking is disabled, ignoring requestLocationUpdate.")
            return
        }

        val authStatus = locationProvider.currentAuthorizationStatus()
        if (!authStatus.isAuthorized) {
            logger.debug("Location permission not granted ($authStatus), ignoring request.")
            return
        }

        try {
            val snapshot = locationProvider.requestLocation(
                granularity = LocationGranularity.DEFAULT
            )
            logger.debug("Tracking location: lat=${snapshot.latitude}, lng=${snapshot.longitude}")
            // Read at delivery so a mid-flight upgrade routes the same fix tracked. The OFF gate
            // re-applies — an upgrade must never make a disabled tracking mode emit analytics.
            if (intent.isTracked && config.isEnabled) {
                locationTracker.onLocationReceived(snapshot.latitude, snapshot.longitude)
            } else {
                locationTracker.onLocationReceivedWithoutTracking(snapshot.latitude, snapshot.longitude)
            }
        } catch (e: CancellationException) {
            logger.debug("Location request was cancelled.")
            throw e
        } catch (e: LocationRequestException) {
            logger.debug("Location request failed: ${e.error}")
        } catch (e: Exception) {
            logger.error("Location request failed with unexpected error: ${e.message}")
        }
    }
}

/**
 * Mutable intent of one in-flight location request. Starts tracked (analytics) or silent
 * (geofence-only) and can only be upgraded: a tracked request arriving while a silent
 * fetch is in flight consumes the same fix instead of being dropped.
 */
internal class LocationRequestIntent(tracked: Boolean) {
    private val tracked = AtomicBoolean(tracked)
    val isTracked: Boolean get() = tracked.get()
    fun upgradeToTracked() = tracked.set(true)
}
