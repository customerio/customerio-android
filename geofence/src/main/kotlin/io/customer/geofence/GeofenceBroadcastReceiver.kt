package io.customer.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.geofence.di.geofenceCrossingPipeline
import io.customer.geofence.di.geofenceLogger
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
        // goAsync keeps the process alive until WorkManager has committed the work spec;
        // without it the OS may kill us between enqueue and persist.
        val pendingResult = goAsync()
        try {
            SDKComponent.setupAndroidComponent(context = context)
            val scope = SDKComponent.scopeProvider.geofenceScope
            launchTransitionHandler(scope, intent, pendingResult)
        } catch (e: Throwable) {
            // Setup threw before the coroutine could register its finally — release the PendingResult here.
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

    /**
     * Translates one GMS broadcast into a [GeofenceCrossing] and hands it on.
     *
     * Everything here is the OS half: unwrapping the event, reading Play Services' error channel,
     * and logging the fix while the whole `Location` still exists. No routing, no drops, no
     * containment — those moved to [GeofenceCrossingPipeline], which never sees a GMS type.
     */
    @VisibleForTesting
    internal suspend fun handleGeofencingEvent(geofencingEvent: GeofencingEvent?) {
        val logger = SDKComponent.geofenceLogger
        if (geofencingEvent == null) {
            logger.logInfo("broadcast_unparseable_intent")
            return
        }

        if (geofencingEvent.hasError()) {
            logger.logGeofencingError(geofencingEvent.errorCode)
            return
        }

        val triggeringGeofenceIds = geofencingEvent.triggeringGeofences?.map { it.requestId }
            ?: run {
                logger.logInfo("broadcast_no_triggering_geofences")
                return
            }
        val location = geofencingEvent.triggeringLocation
        val gmsTransition = geofencingEvent.geofenceTransition
        // Logged here, before any routing decision and before the Location is narrowed to a pair
        // of doubles. This is the only place the OS's own triggering fix — accuracy, age, mock
        // flag and all — still exists.
        logger.logCallbackReceived(
            geofenceIds = triggeringGeofenceIds,
            transitionName = transitionName(gmsTransition),
            location = location,
            source = if (location != null) GeofenceLogTail.FixSource.OS_TRIGGER else GeofenceLogTail.FixSource.NONE
        )
        if (location == null) {
            logger.logTransitionWithoutLocation()
        }

        dispatchCrossing(
            GeofenceCrossing(
                geofenceIds = triggeringGeofenceIds,
                transition = crossingTransition(gmsTransition),
                transitionName = transitionName(gmsTransition),
                rawTransitionCode = gmsTransition,
                latitude = location?.latitude,
                longitude = location?.longitude
            )
        )
    }

    /**
     * Runs the crossing and holds the `goAsync` window open until any movement refresh it started
     * has landed, so the OS doesn't kill a backgrounded process mid-re-registration.
     *
     * Waits only for what is left of the dispatch budget — the pipeline's own persistence and GMS
     * awaits count against it. A timeout ends the wait only, not the refresh, which runs on the
     * longer-lived services scope.
     */
    private suspend fun dispatchCrossing(crossing: GeofenceCrossing) {
        val startedAtUptimeMs = SDKComponent.clock.elapsedRealtime()
        val refreshJob = SDKComponent.android().geofenceCrossingPipeline.handle(crossing)
        refreshJob?.let { job ->
            val remainingBudgetMs = DISPATCH_WAIT_BUDGET_MS - (SDKComponent.clock.elapsedRealtime() - startedAtUptimeMs)
            if (remainingBudgetMs > 0) {
                withTimeoutOrNull(remainingBudgetMs) { job.join() }
            }
        }
    }

    private fun crossingTransition(gmsTransitionType: Int): GeofenceCrossingTransition = when (gmsTransitionType) {
        Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceCrossingTransition.ENTER
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
        // goAsync grants ~10s before the OS considers the receiver blocked; total budget for
        // one dispatch (persistence + GMS awaits + movement-refresh wait), with headroom.
        private const val DISPATCH_WAIT_BUDGET_MS = 8_000L
    }
}
