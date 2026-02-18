package io.customer.location

import io.customer.location.provider.LocationProvider
import io.customer.location.provider.LocationRequestException
import io.customer.location.type.LocationGranularity
import io.customer.sdk.communication.Event
import io.customer.sdk.communication.EventBus
import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.CancellationException

/**
 * Coordinates location requests: one-shot only.
 * Uses an injected [LocationProvider] for platform location access.
 */
internal class LocationOrchestrator(
    private val config: LocationModuleConfig,
    private val logger: Logger,
    private val eventBus: EventBus,
    private val locationProvider: LocationProvider
) {

    suspend fun requestLocationUpdate() {
        if (!config.enableLocationTracking) {
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
            postLocation(snapshot.latitude, snapshot.longitude)
        } catch (e: CancellationException) {
            logger.debug("Location request was cancelled.")
            throw e
        } catch (e: LocationRequestException) {
            logger.debug("Location request failed: ${e.error}")
        } catch (e: Exception) {
            logger.error("Location request failed with unexpected error: ${e.message}")
        }
    }

    private fun postLocation(latitude: Double, longitude: Double) {
        logger.debug("Tracking location: lat=$latitude, lng=$longitude")
        val locationData = Event.LocationData(
            latitude = latitude,
            longitude = longitude
        )
        eventBus.publish(Event.TrackLocationEvent(location = locationData))
    }
}
