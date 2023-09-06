package io.customer.messagingpush.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.config.NotificationClickBehavior
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.extensions.parcelable
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.extensions.takeIfNotBlank
import io.customer.sdk.tracking.TrackableScreen
import io.customer.sdk.util.Logger

class NotificationClickReceiverActivity : Activity(), TrackableScreen {
    val logger: Logger by lazy { CustomerIOShared.instance().diStaticGraph.logger }

    override fun getScreenName(): String? {
        // Return null to prevent this screen from being tracked
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(data = intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(data = intent)
    }

    private fun handleIntent(data: Intent?) {
        kotlin.runCatching {
            val payload: CustomerIOParsedPushPayload? =
                data?.extras?.parcelable(NOTIFICATION_PAYLOAD_EXTRA)
            if (payload == null) {
                logger.error("Payload is null, cannot handle notification intent")
            } else {
                processNotificationIntent(payload = payload)
            }
        }.onFailure { ex ->
            logger.error("Failed to process notification intent: ${ex.message}")
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

        val deepLink = payload.deepLink?.takeIfNotBlank()
        if (deepLink == null) {
            logger.debug("No deep link received in push notification content")
        }

        // Get the default intent for the host app
        val defaultHostAppIntent = deepLinkUtil.createDefaultHostAppIntent(
            context = this,
            contentActionLink = null
        )
        // Check if the deep links are handled within the host app
        val deepLinkHostAppIntent = deepLink?.let { link ->
            deepLinkUtil.createDeepLinkHostAppIntent(context = this, link = link)
        }
        // Check if the deep links can be opened outside the host app
        val deepLinkExternalIntent = deepLink?.let { link ->
            deepLinkUtil.createDeepLinkExternalIntent(
                context = this,
                link = link,
                startingFromService = true
            )
        }
        val deepLinkIntent: Intent = deepLinkHostAppIntent
            ?: deepLinkExternalIntent
            ?: defaultHostAppIntent
            ?: return
        deepLinkIntent.putExtra(NOTIFICATION_PAYLOAD_EXTRA, payload)
        logger.debug("[DEV] Dispatching deep link intent: $deepLinkIntent with behavior: ${moduleConfig.notificationOnClickBehavior}")

        when (moduleConfig.notificationOnClickBehavior) {
            NotificationClickBehavior.RESET_TASK_STACK -> {
                val taskStackBuilder =
                    moduleConfig.notificationCallback?.createTaskStackFromPayload(
                        this,
                        payload
                    ) ?: kotlin.run {
                        return@run TaskStackBuilder.create(this).run {
                            addNextIntentWithParentStack(deepLinkIntent)
                        }
                    }
                if (taskStackBuilder.intentCount > 0) {
                    taskStackBuilder.startActivities()
                }
            }

            NotificationClickBehavior.ACTIVITY_PREVENT_RESTART -> {
                deepLinkIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(deepLinkIntent)
            }

            NotificationClickBehavior.ACTIVITY_NO_FLAGS -> {
                startActivity(deepLinkIntent)
            }
        }
    }

    companion object {
        const val NOTIFICATION_PAYLOAD_EXTRA = "CIO_NotificationPayloadExtras"
    }
}
