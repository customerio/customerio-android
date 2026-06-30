package io.customer.geofence

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.customer.geofence.store.PendingGeofenceDelivery
import io.customer.sdk.communication.EventBus
import io.customer.sdk.data.store.PendingDeliveryFlusher

/**
 * Lifecycle observer registered with `ProcessLifecycleOwner` during the
 * geofence module's initialization. On every foreground entry it flushes
 * pending OS-delivered geofence transitions through the analytics pipeline —
 * unconditionally, independent of the location module's tracking mode, so a
 * customer using geofencing with MANUAL location tracking still gets pending
 * transitions delivered.
 *
 * The shared [PendingDeliveryFlusher] cancels each transition's WorkManager
 * delivery and atomically claims it before publishing here, so this path stays
 * the primary deliverer; the worker (send-then-remove) can still race a
 * duplicate via direct HTTP, deduped downstream by transitionId. The entry's
 * snapshotted userId rides through on [io.customer.sdk.communication.Event.GeofenceTransitionEvent]
 * so the pipeline subscriber attributes the track event to it.
 *
 * Thread safety: all lifecycle callbacks are delivered on the main thread
 * by `ProcessLifecycleOwner`, so no synchronization is needed.
 */
internal class GeofenceLifecycleObserver(
    private val deliveryFlusher: PendingDeliveryFlusher<PendingGeofenceDelivery>,
    private val eventBus: EventBus,
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
            }
        ) { entry ->
            eventBus.publish(entry.toGeofenceTransitionEvent())
        }
    }
}
