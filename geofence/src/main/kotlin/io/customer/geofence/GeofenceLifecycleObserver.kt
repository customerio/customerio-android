package io.customer.geofence

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.store.withFreshestEventData
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.PendingDeliveryFlusher

/**
 * Registered with `ProcessLifecycleOwner` at geofence-module init. On every foreground
 * entry it flushes pending OS-delivered transitions through the analytics pipeline,
 * independent of the location tracking mode (so MANUAL still delivers).
 *
 * The shared [PendingDeliveryFlusher] runs [PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE]:
 * publish, remove, then best-effort cancel the WorkManager delivery. The worker
 * (send-then-remove) is the durable channel while the row exists; duplicates across the
 * two are deduped downstream by transitionId. The entry's snapshotted userId rides through
 * on [io.customer.sdk.communication.Event.GeofenceTransitionEvent] for attribution.
 *
 * All lifecycle callbacks arrive on the main thread, so no synchronization is needed.
 */
internal class GeofenceLifecycleObserver(
    private val deliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery>,
    private val eventBus: EventBus,
    private val regionStore: GeofenceRegionStore,
    private val logger: GeofenceLogger
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        flushPendingGeofenceDeliveries()
    }

    private fun flushPendingGeofenceDeliveries() {
        deliveryFlusher.flush(
            callbacks = object : PendingDeliveryFlusher.Callbacks<PendingGeofenceDelivery>() {
                override fun onSnapshot(count: Int) = logger.logForegroundFlushSnapshot(count)
                override fun onWorkCancelled(entry: PendingGeofenceDelivery) =
                    logger.logForegroundFlushCancelledWorkManager(entry.geofenceId, entry.transition.name)
                override fun onPublished(entry: PendingGeofenceDelivery) =
                    logger.logForegroundFlushPublished(entry.geofenceId, entry.transition.name)
                override fun onEntryFailed(entry: PendingGeofenceDelivery, cause: Throwable) =
                    logger.logForegroundFlushEntryFailed(entry.geofenceId, entry.transition.name, cause.message)
                override fun onComplete(count: Int) = logger.logForegroundFlushComplete(count)
            },
            guarantee = PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE
        ) { entry ->
            eventBus.publish(
                entry.withFreshestEventData(regionStore.getCachedRegion(entry.geofenceId)).toGeofenceTransitionEvent()
            )
        }
    }
}
