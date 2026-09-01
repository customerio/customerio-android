package io.customer.location

import io.customer.location.provider.LocationProvider
import io.customer.location.provider.LocationRequestException
import io.customer.location.type.LocationGranularity
import io.customer.sdk.core.util.Logger
import java.util.concurrent.atomic.AtomicReference
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
        // Closed on every exit path, so a tracked request can never upgrade onto this one once it
        // can no longer deliver.
        try {
            acquireLocation(intent)
        } finally {
            intent.finish()
        }
    }

    private suspend fun acquireLocation(intent: LocationRequestIntent) {
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
            // Claimed at delivery so a mid-flight upgrade routes this same fix, and a later one is
            // rejected rather than lost. The OFF re-check keeps an upgrade from emitting analytics
            // for a disabled tracking mode.
            if (intent.claimTrackedDelivery() && config.isEnabled) {
                locationTracker.onLocationReceived(
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude,
                    horizontalAccuracyMeters = snapshot.horizontalAccuracy,
                    fixTimeMillis = snapshot.timestamp.time
                )
            } else {
                locationTracker.onLocationReceivedWithoutTracking(
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude,
                    horizontalAccuracyMeters = snapshot.horizontalAccuracy,
                    fixTimeMillis = snapshot.timestamp.time
                )
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
 *
 * Closed once the request can no longer deliver, so a late upgrade is rejected instead of
 * swallowed and the caller knows to fetch its own fix.
 */
internal class LocationRequestIntent(tracked: Boolean) {
    private val state = AtomicReference(if (tracked) State.TRACKED else State.SILENT)

    /** Peek for the pre-fetch tracking-mode gate; does not close the intent. */
    val isTracked: Boolean get() = state.get() == State.TRACKED

    /** Reports whether the fix routes tracked, closing the intent so no later upgrade is lost. */
    fun claimTrackedDelivery(): Boolean = state.getAndSet(State.DONE) == State.TRACKED

    /** Closes a request that ended without delivering. */
    fun finish() = state.set(State.DONE)

    /** Upgrades a silent request to tracked. False once closed: the caller must fetch its own. */
    fun upgradeToTracked(): Boolean {
        while (true) {
            when (state.get()) {
                State.DONE -> return false
                State.TRACKED -> return true
                State.SILENT -> if (state.compareAndSet(State.SILENT, State.TRACKED)) return true
            }
        }
    }

    private enum class State { SILENT, TRACKED, DONE }
}
