package io.customer.geofence

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.customer.geofence.store.GeofenceRegionStore
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.geofence.store.withFreshestEventData
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.PendingDeliveryFlusher

/**
 * Registered with `ProcessLifecycleOwner` at module init. On every foreground entry it flushes
 * pending OS-delivered transitions through the analytics pipeline, regardless of location mode
 * (so MANUAL still delivers), then runs [onForeground].
 *
 * The flush is [PendingDeliveryFlusher.DeliveryGuarantee.AT_LEAST_ONCE] (publish, then remove). It
 * does not cancel the shared WorkManager chain; a queued worker finds the row gone and sends
 * nothing. Duplicates are deduped downstream by transitionId.
 *
 * Lifecycle callbacks arrive on the main thread, so no synchronization is needed.
 */
internal class GeofenceLifecycleObserver(
    private val deliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery>,
    private val eventBus: EventBus,
    private val regionStore: GeofenceRegionStore,
    private val permissionReporter: GeofencePermissionReporter,
    private val logger: GeofenceLogger,
    private val onForeground: () -> Unit
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        // First, since the flush and refresh behave differently by tier. Shared with module init, so
        // an unchanged tier is not logged twice.
        permissionReporter.reportIfChanged()
        flushPendingGeofenceDeliveries()
        onForeground()
    }

    private fun flushPendingGeofenceDeliveries() {
        deliveryFlusher.flush(
            callbacks = object : PendingDeliveryFlusher.Callbacks<PendingGeofenceDelivery>() {
                override fun onSnapshot(count: Int) = logger.logForegroundFlushSnapshot(count)
                override fun onUnreadable() = logger.logForegroundFlushQueueUnreadable()
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
