package io.customer.messagingpush.processor

import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.sdk.data.store.PendingDeliveryStore

/**
 * Tracks Delivered for silent pushes the same way [PushMessageProcessorImpl] does for regular pushes.
 * That processor keeps the config it is built with, so a silent push that starts the app before setup
 * goes through this class, which reads the config for each message.
 */
internal class BackgroundPushDeliveryMetrics(
    private val pushLogger: PushNotificationLogger,
    private val moduleConfig: () -> MessagingPushModuleConfig,
    private val pendingStore: PendingDeliveryStore<PendingPushDeliveryMetric>,
    private val deliveryMetricsScheduler: PushDeliveryMetricsBackgroundScheduler
) {
    private val recentDeliveryIds = ArrayDeque<String>()

    fun trackDelivered(deliveryId: String, deliveryToken: String) {
        if (!markNew(deliveryId)) return
        if (!moduleConfig().autoTrackPushEvents) {
            pushLogger.logPushMetricsAutoTrackingDisabled()
            return
        }
        pushLogger.logTrackingPushMessageDelivered(deliveryId)
        pushLogger.logPendingStoreAppended(deliveryId)
        pendingStore.append(PendingPushDeliveryMetric(deliveryId = deliveryId, token = deliveryToken))
        deliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(deliveryId = deliveryId, deliveryToken = deliveryToken)
    }

    @Synchronized
    private fun markNew(deliveryId: String): Boolean {
        if (deliveryId in recentDeliveryIds) return false
        if (recentDeliveryIds.size >= RECENT_DELIVERY_IDS_MAX_SIZE) recentDeliveryIds.removeLast()
        recentDeliveryIds.addFirst(deliveryId)
        return true
    }

    private companion object {
        const val RECENT_DELIVERY_IDS_MAX_SIZE = 10
    }
}
