package io.customer.messagingpush.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.extensions.parcelable
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.util.Logger

class NotificationClickReceiverActivity : Activity() {
    val logger: Logger by lazy { CustomerIOShared.instance().diStaticGraph.logger }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(data = intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(data = intent)
    }

    private fun handleIntent(data: Intent?) {
        val payload: CustomerIOParsedPushPayload? =
            data?.extras?.parcelable(NOTIFICATION_PAYLOAD_EXTRA)
        if (payload == null) {
            logger.error("Payload is null, cannot handle notification intent")
        } else {
            processNotificationIntent(payload = payload)
        }
        finish()
    }

    private fun processNotificationIntent(payload: CustomerIOParsedPushPayload) {
        val sdkInstance = CustomerIO.instanceOrNull(context = this)
        if (sdkInstance == null) {
            logger.error("SDK is not initialized, cannot handle notification intent")
            return
        }

        val diGraph = sdkInstance.diGraph
        val deepLinkUtil: DeepLinkUtil = diGraph.deepLinkUtil
        val moduleConfig: MessagingPushModuleConfig = diGraph.moduleConfig

        val deliveryId = payload.cioDeliveryId
        val deliveryToken = payload.cioDeliveryToken

        if (moduleConfig.autoTrackPushEvents) {
            sdkInstance.trackMetric(deliveryId, MetricEvent.opened, deliveryToken)
        }

        // check if host app overrides the handling of deeplink
        val notificationCallback = moduleConfig.notificationCallback
        val taskStackBuilder = notificationCallback?.createTaskStackFromPayload(this, payload)
        if (taskStackBuilder != null) {
            taskStackBuilder.startActivities()
            return
        }

        val deepLinkIntent = deepLinkUtil.createDeepLinkHostAppIntent(
            // check if the deep links are handled within the host app
            this,
            payload.deepLink
        ) ?: payload.deepLink?.let { link ->
            // check if the deep links can be opened outside the host app
            deepLinkUtil.createDeepLinkExternalIntent(
                context = this,
                link = link,
                startingFromService = true
            )
        } ?: deepLinkUtil.createDefaultHostAppIntent(
            context = this,
            contentActionLink = null
        )

        deepLinkIntent?.let { intent ->
            try {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                startActivity(intent)
            } catch (ex: ActivityNotFoundException) {
                logger.error("Unable to start activity for notification action $payload; ${ex.message}")
            }
        }
    }

    companion object {
        const val NOTIFICATION_PAYLOAD_EXTRA = "CIO-Notification-Payload"
    }
}
