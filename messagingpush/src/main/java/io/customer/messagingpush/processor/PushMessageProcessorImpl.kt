package io.customer.messagingpush.processor

import android.content.Intent
import androidx.annotation.VisibleForTesting
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.module.CustomerIOAnalytics
import io.customer.sdk.util.Logger

internal class PushMessageProcessorImpl(
    private val logger: Logger,
    private val moduleConfig: MessagingPushModuleConfig,
    private val trackRepository: CustomerIOAnalytics
) : PushMessageProcessor {

    /**
     * Responsible for storing and updating recent messages in queue atomically.
     * Once this method is called, the current implementation marks the notification
     * as processed and future calls for same [deliveryId] will receive true indicating
     * that the notification is processed already.
     *
     * @param deliveryId unique message id received in push payload.
     * @return true if the message was processed previously; false otherwise.
     * Callers should generally process notifications only if false was returned.
     */
    @Synchronized
    @VisibleForTesting
    internal fun getOrUpdateMessageAlreadyProcessed(deliveryId: String?): Boolean {
        when {
            deliveryId.isNullOrBlank() -> {
                // Ignore messages with empty/invalid deliveryId
                logger.debug("Received message with empty deliveryId")
                return true
            }

            PushMessageProcessor.recentMessagesQueue.contains(deliveryId) -> {
                // Ignore messages that were processed already
                logger.debug("Received duplicate message with deliveryId: $deliveryId")
                return true
            }

            else -> {
                // Else add this deliveryId to the queue and assure queue don't exhaust its capacity
                if (PushMessageProcessor.recentMessagesQueue.size >= PushMessageProcessor.RECENT_MESSAGES_MAX_SIZE) {
                    PushMessageProcessor.recentMessagesQueue.removeLast()
                }
                PushMessageProcessor.recentMessagesQueue.addFirst(deliveryId)
                logger.debug("Received new message with deliveryId: $deliveryId")
                return false
            }
        }
    }

    override fun processGCMMessageIntent(intent: Intent) {
        val bundle = intent.extras
        val deliveryId = bundle?.getString(PushTrackingUtil.DELIVERY_ID_KEY)
        val deliveryToken = bundle?.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY)
        // Not a CIO push notification
        if (deliveryId == null || deliveryToken == null) return

        if (!getOrUpdateMessageAlreadyProcessed(deliveryId = deliveryId)) {
            // We only track delivered metrics from GCM right now
            trackDeliveredMetrics(deliveryId, deliveryToken)
        }
    }

    override fun processRemoteMessageDeliveredMetrics(deliveryId: String, deliveryToken: String) {
        if (!getOrUpdateMessageAlreadyProcessed(deliveryId = deliveryId)) {
            // We only track delivered metrics here for Firebase messages as the caller
            // processes and displays the notification already and FCM guarantees to send
            // callbacks only once for each notification, so duplication is not possible within
            // Firebase message receivers
            trackDeliveredMetrics(deliveryId, deliveryToken)
        }
    }

    private fun trackDeliveredMetrics(deliveryId: String, deliveryToken: String) {
        // Track delivered event only if auto-tracking is enabled
        if (moduleConfig.autoTrackPushEvents) {
            trackRepository.trackMetric(
                deliveryID = deliveryId,
                deviceToken = deliveryToken,
                event = MetricEvent.delivered
            )
        }
    }
}
