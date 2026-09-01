package io.customer.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.customer.geofence.di.geofenceLogger
import io.customer.geofence.di.geofenceManager
import io.customer.geofence.di.geofenceRegionStore
import io.customer.geofence.di.geofenceServices
import io.customer.geofence.di.geofenceTransitionEmitter
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.AndroidSDKComponent
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.clock
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    @VisibleForTesting
    internal suspend fun handleGeofencingEvent(geofencingEvent: GeofencingEvent?) {
        if (geofencingEvent == null) return
        val logger = SDKComponent.geofenceLogger

        if (geofencingEvent.hasError()) {
            logger.logGeofencingError(geofencingEvent.errorCode)
            return
        }

        val triggeringGeofenceIds = geofencingEvent.triggeringGeofences?.map { it.requestId } ?: return
        val location = geofencingEvent.triggeringLocation
        if (location == null) {
            logger.logTransitionWithoutLocation()
        }

        dispatchTransition(
            gmsTransitionType = geofencingEvent.geofenceTransition,
            triggeringGeofenceIds = triggeringGeofenceIds,
            latitude = location?.latitude,
            longitude = location?.longitude,
            quality = GeofenceFixQuality(
                // No age: the OS delivers this fix with the trigger it caused, so it cannot be cached.
                accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble()
            )
        )
    }

    @VisibleForTesting
    internal suspend fun dispatchTransition(
        gmsTransitionType: Int,
        triggeringGeofenceIds: List<String>,
        latitude: Double?,
        longitude: Double?,
        quality: GeofenceFixQuality = GeofenceFixQuality.UNKNOWN
    ) {
        val logger = SDKComponent.geofenceLogger
        val timestamp = SDKComponent.clock.currentTimeSeconds()
        val dispatchStartUptimeMs = SDKComponent.clock.elapsedRealtime()
        val androidComponent = SDKComponent.android()
        // Defense-in-depth against orphans (failed clearAll, app-data wipe, SDK
        // ID-format changes): events for unregistered IDs are dropped and the OS-side
        // registration is removed so it stops firing.
        val registeredIds = androidComponent.geofenceRegionStore.getRegisteredIds()
        val (knownIds, unknownIds) = triggeringGeofenceIds.partition { it in registeredIds }
        if (unknownIds.isNotEmpty()) {
            unknownIds.forEach { logger.logTransitionDroppedUnknownId(it) }
            // Result ignored — a failed removal self-heals on the next orphan event.
            androidComponent.geofenceManager.removeGeofencesByIds(unknownIds)
        }

        var movementRefreshJob: Job? = null
        knownIds.forEach { geofenceId ->
            if (geofenceId == GeofenceConstants.MOVEMENT_TRIGGER_ID) {
                // ENTER fires on every re-registration and boot-restore can fire
                // EXIT. Only EXIT drives a refresh.
                if (gmsTransitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
                    movementRefreshJob =
                        androidComponent.geofenceServices.onMovementTriggerExit(latitude, longitude, quality)
                } else {
                    logger.logMovementTriggerIgnoredNonExit(transitionName(gmsTransitionType))
                }
                return@forEach
            }

            val transition = when (gmsTransitionType) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> Event.GeofenceTransition.ENTER
                Geofence.GEOFENCE_TRANSITION_EXIT -> Event.GeofenceTransition.EXIT
                else -> {
                    logger.logUnknownTransition(gmsTransitionType)
                    return@forEach
                }
            }

            // Each broadcast runs on its own scope, so two transitions for one fence would
            // otherwise interleave read-decide-persist and lose a containment record.
            transitionMutex.withLock {
                handleBusinessTransition(
                    geofenceId = geofenceId,
                    transition = transition,
                    timestamp = timestamp,
                    androidComponent = androidComponent,
                    logger = logger
                )
            }
        }

        // Hold the goAsync window open until the refresh lands so the OS doesn't kill a
        // backgrounded process mid-re-registration. Waits only for what's left of the
        // dispatch budget — the persistence/GMS awaits above count against it. A timeout
        // ends the wait only, not the refresh (it runs on the longer-lived services scope).
        movementRefreshJob?.let { job ->
            val remainingBudgetMs = DISPATCH_WAIT_BUDGET_MS - (SDKComponent.clock.elapsedRealtime() - dispatchStartUptimeMs)
            if (remainingBudgetMs > 0) {
                withTimeoutOrNull(remainingBudgetMs) { job.join() }
            }
        }
    }

    private suspend fun handleBusinessTransition(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        timestamp: Long,
        androidComponent: AndroidSDKComponent,
        logger: GeofenceLogger
    ) {
        // Ahead of the identity checks below: being inside a fence is a physical fact, independent of
        // whether the transition is deliverable.
        val store = androidComponent.geofenceRegionStore
        val cachedRegion = store.getCachedRegion(geofenceId)
        when (transition) {
            Event.GeofenceTransition.ENTER -> store.recordEntered(geofenceId)
            // An unmatched EXIT is a GMS reconciliation artifact, not a crossing — delivering it
            // fires EXIT campaigns at people who were never there. The two guards after claimExit
            // cover the cases where an absent record proves nothing: an upgraded install with no set
            // yet, and a region that never monitored ENTER (a missing cache row counts as unknown).
            Event.GeofenceTransition.EXIT -> if (
                !store.claimExit(geofenceId) &&
                store.hasContainmentRecord() &&
                cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.ENTER) == true
            ) {
                logger.logExitDroppedNeverEntered(geofenceId)
                return
            }
        }

        // Snapshot userId so a sign-out + sign-in before delivery can't reattribute this
        // transition. Empty userId is treated as "not identified" per `isUserIdentified`.
        val userId = androidComponent.secureUserStore.getUserId()?.takeIf { it.isNotEmpty() }
        // Identified-only: the backend rejects anonymous geofence tracks, so drop before spending a
        // cooldown slot or persisting a row neither channel could send.
        if (userId == null) {
            logger.logTransitionDroppedAnonymous(geofenceId, transition.name)
            return
        }

        androidComponent.geofenceTransitionEmitter.emit(
            geofenceId = geofenceId,
            transition = transition,
            userId = userId,
            timestampSeconds = timestamp,
            geofenceName = cachedRegion?.name,
            metadata = cachedRegion?.metadata ?: emptyMap(),
            geosetIds = cachedRegion?.geosetIds ?: emptyList(),
            monitorsExit = cachedRegion?.transitionTypes?.contains(GeofenceTransitionType.EXIT) == true
        )
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

        // Process-wide: a receiver instance lives for one broadcast, so the lock has to outlive it.
        private val transitionMutex = Mutex()
    }
}
