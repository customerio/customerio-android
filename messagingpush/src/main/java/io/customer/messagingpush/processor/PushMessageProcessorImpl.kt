package io.customer.messagingpush.processor

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.config.PushClickBehavior
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.extensions.parcelable
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.events.Metric

internal class PushMessageProcessorImpl(
    private val pushLogger: PushNotificationLogger,
    private val moduleConfig: MessagingPushModuleConfig,
    private val deepLinkUtil: DeepLinkUtil,
    private val deliveryMetricsScheduler: PushDeliveryMetricsBackgroundScheduler
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
                pushLogger.logReceivedPushMessageWithEmptyDeliveryId()
                return true
            }

            PushMessageProcessor.recentMessagesQueue.contains(deliveryId) -> {
                // Ignore messages that were processed already
                pushLogger.logReceivedDuplicatePushMessageDeliveryId(deliveryId)
                return true
            }

            else -> {
                // Else add this deliveryId to the queue and assure queue don't exhaust its capacity
                if (PushMessageProcessor.recentMessagesQueue.size >= PushMessageProcessor.RECENT_MESSAGES_MAX_SIZE) {
                    PushMessageProcessor.recentMessagesQueue.removeLast()
                }
                PushMessageProcessor.recentMessagesQueue.addFirst(deliveryId)
                pushLogger.logReceivedNewMessageWithDeliveryId(deliveryId)
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
            pushLogger.logTrackingPushMessageDelivered(deliveryId)

            deliveryMetricsScheduler.scheduleDeliveredPushMetricsReceipt(deliveryId, deliveryToken)

            // Track delivered metrics via event bus
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = deliveryId,
                    deviceToken = deliveryToken
                )
            )
        } else {
            pushLogger.logPushMetricsAutoTrackingDisabled()
        }
    }

    override fun processNotificationClick(activityContext: Context, intent: Intent) {
        kotlin.runCatching {
            val payload: CustomerIOParsedPushPayload? =
                intent.extras?.parcelable(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA)
            if (payload == null) {
                pushLogger.logFailedToHandlePushClick(IllegalArgumentException("Payload is null, cannot handle notification intent"))
            } else {
                handleNotificationClickIntent(activityContext, payload)
            }
        }.onFailure { ex ->
            pushLogger.logFailedToHandlePushClick(ex)
        }
    }

    private fun handleNotificationClickIntent(
        activityContext: Context,
        payload: CustomerIOParsedPushPayload
    ) {
        trackNotificationClickMetrics(payload)
        handleNotificationDeepLink(activityContext, payload)
    }

    private fun trackNotificationClickMetrics(payload: CustomerIOParsedPushPayload) {
        if (moduleConfig.autoTrackPushEvents) {
            pushLogger.logTrackingPushMessageOpened(payload)
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Opened,
                    deliveryId = payload.cioDeliveryId,
                    deviceToken = payload.cioDeliveryToken
                )
            )
        } else {
            pushLogger.logPushMetricsAutoTrackingDisabled()
        }
    }

    private fun handleNotificationDeepLink(
        activityContext: Context,
        payload: CustomerIOParsedPushPayload
    ) {
        pushLogger.logHandlingNotificationDeepLink(payload, moduleConfig.pushClickBehavior)
        val deepLink = payload.deepLink?.takeIf { link -> link.isNotBlank() }

        // check if host app overrides the handling of deeplink
        val notificationCallback = moduleConfig.notificationCallback
        val wasClicked = notificationCallback?.onNotificationClicked(
            context = activityContext,
            payload = payload
        )

        if (wasClicked != null) {
            pushLogger.logDeepLinkHandledByCallback()
            return
        }

        // Check if the deep links are handled within the host app
        val deepLinkHostAppIntent = deepLink?.let { link ->
            deepLinkUtil.createDeepLinkHostAppIntent(context = activityContext, link = link)
        }
        // Check if the deep links are handled externally only if the host app doesn't handle it
        if (deepLinkHostAppIntent == null) {
            // Check if the deep links can be opened outside the host app
            val deepLinkExternalIntent = deepLink?.let { link ->
                deepLinkUtil.createDeepLinkExternalIntent(context = activityContext, link = link)
            }
            // Check if the deep links should be opened externally
            if (deepLinkExternalIntent != null) {
                // Open link externally and return
                pushLogger.logDeepLinkHandledExternally()
                activityContext.startActivity(deepLinkExternalIntent)
                return
            }
        }

        // Get the default intent for the host app
        val defaultHostAppIntent =
            deepLinkUtil.createDefaultHostAppIntent(context = activityContext)

        when {
            deepLinkHostAppIntent != null -> {
                pushLogger.logDeepLinkHandledByHostApp()
                startActivityForIntent(activityContext, deepLinkHostAppIntent, payload)
            }
            defaultHostAppIntent != null -> {
                pushLogger.logDeepLinkHandledDefaultHostAppLauncher()
                startActivityForIntent(activityContext, defaultHostAppIntent, payload)
            }
            else -> pushLogger.logDeepLinkWasNotHandled()
        }
    }

    private fun startActivityForIntent(
        activityContext: Context,
        deepLinkIntent: Intent,
        payload: CustomerIOParsedPushPayload
    ) {
        deepLinkIntent.putExtra(
            NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA,
            payload
        )

        when (moduleConfig.pushClickBehavior) {
            PushClickBehavior.RESET_TASK_STACK -> {
                val taskStackBuilder = TaskStackBuilder.create(activityContext).apply {
                    addNextIntentWithParentStack(deepLinkIntent)
                }
                taskStackBuilder.startActivities()
            }

            PushClickBehavior.ACTIVITY_PREVENT_RESTART -> {
                deepLinkIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                activityContext.startActivity(deepLinkIntent)
            }

            PushClickBehavior.ACTIVITY_NO_FLAGS -> {
                activityContext.startActivity(deepLinkIntent)
            }
        }
    }
}
