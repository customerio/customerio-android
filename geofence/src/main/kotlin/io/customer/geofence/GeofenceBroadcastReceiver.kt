package io.customer.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import io.customer.geofence.di.geofenceCrossingPipeline
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.polygonGeofenceServiceController
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Receives OS geofence transition callbacks and dispatches them to the SDK. */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // goAsync keeps the process alive until the crossing is persisted and its delivery scheduled.
        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            val scope = SDKComponent.scopeProvider.geofenceScope
            launchTransitionHandler(scope, intent, pendingResult)
        } catch (e: Throwable) {
            // Setup threw before the coroutine's finally existed, so release the PendingResult here.
            SDKComponent.geofenceLogger.logSyncFailed("BroadcastReceiver setup failed: ${e.message}")
            pendingResult.finish()
        }
    }

    private fun launchTransitionHandler(
        scope: CoroutineScope,
        intent: Intent,
        pendingResult: PendingResult
    ) {
        scope.launch {
            try {
                handleGeofencingEvent(GeofencingEvent.fromIntent(intent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SDKComponent.geofenceLogger.logSyncFailed("BroadcastReceiver error: ${e.message}")
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    @VisibleForTesting
    internal suspend fun handleGeofencingEvent(geofencingEvent: GeofencingEvent?) {
        val logger = SDKComponent.geofenceLogger
        if (geofencingEvent == null) {
            logger.logInfo("broadcast_unparseable_intent")
            return
        }

        if (geofencingEvent.hasError()) {
            logger.logGeofencingError(geofencingEvent.errorCode)
            if (geofencingEvent.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                // GMS requires re-registering after this. Invalidate only the live-registration
                // claim; cached definitions and containment survive so a refresh restores without
                // duplicate ENTERs.
                SDKComponent.android().polygonGeofenceServiceController.invalidateOsRegistrationState()
            }
            return
        }

        val triggeringGeofenceIds = geofencingEvent.triggeringGeofences?.map { it.requestId }
            ?: run {
                logger.logInfo("broadcast_no_triggering_geofences")
                return
            }
        val location = geofencingEvent.triggeringLocation
        val gmsTransition = geofencingEvent.geofenceTransition
        // The only place the OS's full triggering fix (accuracy, age, mock flag) is still available.
        logger.logCallbackReceived(
            geofenceIds = triggeringGeofenceIds,
            transitionName = transitionName(gmsTransition),
            location = location,
            source = if (location != null) GeofenceLogTail.FixSource.OS_TRIGGER else GeofenceLogTail.FixSource.NONE
        )
        if (location == null) {
            logger.logTransitionWithoutLocation()
        }

        dispatchTransition(
            gmsTransitionType = gmsTransition,
            triggeringGeofenceIds = triggeringGeofenceIds,
            latitude = location?.latitude,
            longitude = location?.longitude,
            triggeringLocation = location
        )
    }

    /** Test seam: a `GeofencingEvent` cannot be built without GMS internals. */
    @VisibleForTesting
    internal suspend fun dispatchTransition(
        gmsTransitionType: Int,
        triggeringGeofenceIds: List<String>,
        latitude: Double?,
        longitude: Double?,
        triggeringLocation: Location? = null
    ) {
        dispatchCrossing(
            GeofenceCrossing(
                geofenceIds = triggeringGeofenceIds,
                transition = crossingTransition(gmsTransitionType),
                transitionName = transitionName(gmsTransitionType),
                rawTransitionCode = gmsTransitionType,
                latitude = latitude,
                longitude = longitude,
                triggeringLocation = triggeringLocation,
                receivedAtSeconds = SDKComponent.clock.currentTimeSeconds()
            )
        )
    }

    /**
     * Holds the goAsync window open for a movement refresh this crossing started, within the
     * remaining dispatch budget. A timeout ends the wait only, not the refresh.
     */
    private suspend fun dispatchCrossing(crossing: GeofenceCrossing) {
        val startedAtUptimeMs = SDKComponent.clock.elapsedRealtime()
        // Resolved apart from handling so it can be timed: a cold process builds the DI singleton here.
        val pipeline = SDKComponent.android().geofenceCrossingPipeline
        SDKComponent.geofenceLogger.logDispatchReady(SDKComponent.clock.elapsedRealtime() - startedAtUptimeMs)
        val refreshJob = pipeline.handle(crossing)
        refreshJob?.let { job ->
            val remainingBudgetMs = DISPATCH_WAIT_BUDGET_MS - (SDKComponent.clock.elapsedRealtime() - startedAtUptimeMs)
            if (remainingBudgetMs > 0) {
                withTimeoutOrNull(remainingBudgetMs) { job.join() }
            }
        }
    }

    private fun crossingTransition(gmsTransitionType: Int): GeofenceCrossingTransition = when (gmsTransitionType) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceCrossingTransition.ENTER
        Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceCrossingTransition.DWELL
        Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceCrossingTransition.EXIT
        else -> GeofenceCrossingTransition.UNSUPPORTED
    }

    private fun transitionName(gmsTransitionType: Int): String = when (gmsTransitionType) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
        Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
        Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
        else -> "UNKNOWN($gmsTransitionType)"
    }

    internal companion object {
        // goAsync grants ~10s before the OS considers the receiver blocked; this leaves headroom.
        private const val DISPATCH_WAIT_BUDGET_MS = 8_000L
    }
}
