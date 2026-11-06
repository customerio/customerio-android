package io.customer.messagingpush.processor

import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.messagingpush.store.PushSettingsStore
import io.customer.sdk.data.store.PendingDeliveryStore

/**
 * Tracks Delivered for silent pushes. It is kept apart from [PushMessageProcessorImpl] so regular
 * pushes keep main's behavior: that processor reads its config once, the first time any push
 * arrives, and dedupes regular delivery ids in its own queue.
 *
 * A silent push often starts the app before it sets up the SDK, so this reads the current config
 * for each message. Before setup it follows the tracking setting this app version saved at its last
 * setup ([PushSettingsStore]). With neither, Delivered waits in [heldStore] until setup.
 *
 * @param registeredModuleConfig the push module's config, or null until the push module is registered.
 */
internal class BackgroundPushDeliveryMetrics(
    private val pushLogger: PushNotificationLogger,
    private val registeredModuleConfig: () -> MessagingPushModuleConfig?,
    private val pushSettingsStore: PushSettingsStore,
    private val heldStore: PendingDeliveryStore<PendingPushDeliveryMetric>,
    private val pendingStore: PendingDeliveryStore<PendingPushDeliveryMetric>,
    private val deliveryMetricsScheduler: PushDeliveryMetricsBackgroundScheduler
) {
    private val recentDeliveryIds = ArrayDeque<String>()

    fun trackDelivered(deliveryId: String, deliveryToken: String) {
        if (!markNew(deliveryId)) return
        when (registeredModuleConfig()?.autoTrackPushEvents ?: pushSettingsStore.autoTrackPushEvents()) {
            null -> {
                pushLogger.logDeliveredMetricsHeld(deliveryId)
                heldStore.append(PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken))
            }
            true -> if (savePending(deliveryId, deliveryToken)) {
                schedule(deliveryId, deliveryToken)
            } else {
                // Keep it for the next release rather than scheduling a worker that finds nothing to send.
                heldStore.append(PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken))
            }
            false -> pushLogger.logPushMetricsAutoTrackingDisabled()
        }
        // After setup, also send or drop metrics held from before it. This covers a hold that raced
        // with setup and apps without a silent push callback, whose setup skips the release.
        if (registeredModuleConfig() != null) releaseHeld()
    }

    /** Sends or drops the metrics held from before setup. Does nothing until the push module is registered. */
    fun releaseHeld() {
        val config = registeredModuleConfig() ?: return
        for (entry in heldStore.loadAll()) {
            // Claiming first keeps two racing releases from tracking the same metric twice.
            if (!heldStore.claim(entry.key)) continue
            if (!config.autoTrackPushEvents) {
                pushLogger.logPushMetricsAutoTrackingDisabled()
            } else if (savePending(entry.deliveryId, entry.token)) {
                schedule(entry.deliveryId, entry.token)
            } else {
                // The pending store didn't save it, so keep the held copy for the next release. Scheduling
                // anyway could send it now through the direct fallback and again from the held copy.
                heldStore.append(entry)
            }
        }
    }

    /** FCM can deliver the same message twice, so only the first copy counts. */
    @Synchronized
    private fun markNew(deliveryId: String): Boolean {
        if (deliveryId in recentDeliveryIds) return false
        if (recentDeliveryIds.size >= RECENT_DELIVERY_IDS_MAX_SIZE) recentDeliveryIds.removeLast()
        recentDeliveryIds.addFirst(deliveryId)
        return true
    }

    private fun savePending(deliveryId: String, deliveryToken: String): Boolean {
        pushLogger.logTrackingPushMessageDelivered(deliveryId)
        pushLogger.logPendingStoreAppended(deliveryId)
        return pendingStore.append(PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken))
    }

    private fun schedule(deliveryId: String, deliveryToken: String) {
        deliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(deliveryId = deliveryId, deliveryToken = deliveryToken)
    }

    private companion object {
        const val RECENT_DELIVERY_IDS_MAX_SIZE = 10
    }
}
