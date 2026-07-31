package io.customer.geofence

import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.worker.GeofenceEventScheduler
import io.customer.sdk.communication.Event
import io.customer.sdk.data.store.PendingDeliveryStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/**
 * Gated, per-geoset fan-out for one crossing: persist the batch, then schedule delivery.
 * Shared by the OS broadcast path ([GeofenceBroadcastReceiver]) and the synthesized initial-enter
 * path ([GeofenceRepository]) so both produce identical rows and pass the same gates.
 *
 * Two gates, in order: an ENTER already reported and not yet balanced by an EXIT is dropped
 * outright, then the time-based cooldown dedupes the rest. Both paths share the first gate, which is
 * why it lives here rather than in the receiver — the synthesized path never touches the receiver.
 */
internal class GeofenceTransitionEmitter(
    private val cooldownFilter: GeofenceCooldownFilter,
    private val pendingStore: PendingDeliveryStore<PendingGeofenceDelivery>,
    private val scheduler: GeofenceEventScheduler,
    private val regionStore: GeofenceRegionStore,
    private val logger: GeofenceLogger
) {
    /**
     * @return false when the ENTER was already reported, the cooldown suppressed it, or the persist
     * failed (cooldown rolled back).
     */
    suspend fun emit(
        geofenceId: String,
        transition: Event.GeofenceTransition,
        userId: String,
        timestampSeconds: Long,
        geofenceName: String?,
        metadata: Map<String, JsonElement>,
        geosetIds: List<String>
    ): Boolean {
        // Every reboot and app update re-registers with INITIAL_TRIGGER_ENTER, so the OS re-reports
        // ENTER for a fence the device never left. Ahead of the cooldown so the drop doesn't spend
        // the fence's slot. Read-then-mark isn't atomic — the mark waits for a durable write below —
        // but tryAcquire is, so two concurrent duplicates still collapse to one.
        if (transition == Event.GeofenceTransition.ENTER && regionStore.hasEmittedEnter(geofenceId)) {
            logger.logEnterDroppedAlreadyReported(geofenceId)
            return false
        }
        if (!cooldownFilter.tryAcquire(userId, geofenceId, transition)) {
            logger.logTransitionSuppressed(geofenceId, transition.name)
            return false
        }
        logger.logTransitionEmitting(geofenceId, transition.name)

        // One transitionId shared across the per-geoset fan-out.
        val transitionId = UUID.randomUUID().toString()
        val name = geofenceName?.takeIf { it.isNotEmpty() }
        // One event per geoset; no geosets → one null-geoset event. Distinct so a repeated geoset
        // doesn't duplicate.
        val geosets: List<String?> = geosetIds.distinct().takeIf { it.isNotEmpty() } ?: listOf(null)
        val entries = geosets.map { geosetId ->
            PendingGeofenceDelivery(
                geofenceId = geofenceId,
                transition = transition,
                timestamp = timestampSeconds,
                userId = userId,
                transitionId = transitionId,
                geofenceName = name,
                geosetId = geosetId,
                metadata = metadata
            )
        }

        // Persist atomically before any send so an app kill mid-batch can't lose part of the fan-out.
        // On write failure there's nothing to deliver: roll back the cooldown so the crossing retries.
        if (!pendingStore.appendAll(entries)) {
            logger.logPersistFailed(geofenceId, transition.name)
            cooldownFilter.release(userId, geofenceId, transition)
            return false
        }
        // Only once the rows are durable, so a rolled-back write can't leave a fence marked as
        // reported and suppress its own retry. The EXIT side is cleared in `claimExit`.
        if (transition == Event.GeofenceTransition.ENTER) {
            regionStore.markEnterEmitted(geofenceId)
        }
        entries.forEach { entry ->
            // Isolate the scheduler so one failure can't abandon the rest of the batch.
            try {
                scheduler.schedule(entry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.logSchedulerFailed(geofenceId, transition.name, e.message)
            }
        }
        return true
    }
}
