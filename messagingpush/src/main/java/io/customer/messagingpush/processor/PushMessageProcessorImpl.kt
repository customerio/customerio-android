package io.customer.messagingpush.processor

import android.content.Intent
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.TrackRepository

internal class PushMessageProcessorImpl(
    private val moduleConfig: MessagingPushModuleConfig,
    private val trackRepository: TrackRepository
) : PushMessageProcessor {

    @Synchronized
    private fun getOrUpdateMessageAlreadyProcessed(deliveryId: String?): Boolean {
        val logger = CustomerIOShared.instance().diStaticGraph.logger

        when {
            deliveryId.isNullOrBlank() -> {
                logger.debug("Received message with empty deliveryId")
                return true
            }

            PushMessageProcessor.recentMessagesQueue.contains(deliveryId) -> {
                logger.debug("Received duplicate message with deliveryId: $deliveryId")
                return true
            }

            else -> {
                // Else add this deliveryId to the queue and assure queue don't exhaust its capacity
                if (PushMessageProcessor.recentMessagesQueue.size >= PushMessageProcessor.RECENT_MESSAGES_MAX_SIZE) {
                    PushMessageProcessor.recentMessagesQueue.removeLast()
                }
                PushMessageProcessor.recentMessagesQueue.addFirst(deliveryId)
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
            trackDeliveredMetrics(deliveryId, deliveryToken)
        }
    }

    override fun processRemoteMessageDeliveredMetrics(deliveryId: String, deliveryToken: String) {
        if (!getOrUpdateMessageAlreadyProcessed(deliveryId = deliveryId)) {
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
