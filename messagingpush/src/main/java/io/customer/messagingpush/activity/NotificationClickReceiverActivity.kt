package io.customer.messagingpush.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.config.PushClickBehavior
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

/**
 * Activity to handle notification click events.
 *
 * This activity is launched when a notification is clicked. It tracks opened
 * metrics, handles the deep link and opens the desired activity in the host app.
 */
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

        val moduleConfig: MessagingPushModuleConfig = sdkInstance.diGraph.moduleConfig
        trackMetrics(moduleConfig, payload)
        handleDeepLink(moduleConfig, payload)
    }

    private fun trackMetrics(
        moduleConfig: MessagingPushModuleConfig,
        payload: CustomerIOParsedPushPayload
    ) {
        if (moduleConfig.autoTrackPushEvents) {
            CustomerIO.instance().trackMetric(
                payload.cioDeliveryId,
                MetricEvent.opened,
                payload.cioDeliveryToken
            )
        }
    }

    private fun handleDeepLink(
        moduleConfig: MessagingPushModuleConfig,
        payload: CustomerIOParsedPushPayload
    ) {
        val deepLinkUtil: DeepLinkUtil = CustomerIO.instance().diGraph.deepLinkUtil
        val deepLink = payload.deepLink?.takeIfNotBlank()

        // check if host app overrides the handling of deeplink
        val notificationCallback = moduleConfig.notificationCallback
        val taskStackFromPayload = notificationCallback?.createTaskStackFromPayload(this, payload)
        if (taskStackFromPayload != null) {
            logger.info("Notification target overridden by createTaskStackFromPayload, starting new stack for link $deepLink")
            taskStackFromPayload.startActivities()
            return
        }

        // Get the default intent for the host app
        val defaultHostAppIntent = deepLinkUtil.createDefaultHostAppIntent(context = this)
        // Check if the deep links are handled within the host app
        val deepLinkHostAppIntent = deepLink?.let { link ->
            deepLinkUtil.createDeepLinkHostAppIntent(context = this, link = link)
        }
        // Check if the deep links can be opened outside the host app
        val deepLinkExternalIntent = deepLink?.let { link ->
            deepLinkUtil.createDeepLinkExternalIntent(context = this, link = link)
        }
        val deepLinkIntent: Intent = deepLinkHostAppIntent
            ?: deepLinkExternalIntent
            ?: defaultHostAppIntent
            ?: return
        deepLinkIntent.putExtra(NOTIFICATION_PAYLOAD_EXTRA, payload)
        logger.info("Dispatching notification with link $deepLink to intent: $deepLinkIntent with behavior: ${moduleConfig.notificationOnClickBehavior}")

        when (moduleConfig.notificationOnClickBehavior) {
            PushClickBehavior.RESET_TASK_STACK -> {
                val taskStackBuilder = TaskStackBuilder.create(this).apply {
                    addNextIntentWithParentStack(deepLinkIntent)
                }
                taskStackBuilder.startActivities()
            }

            PushClickBehavior.ACTIVITY_PREVENT_RESTART -> {
                deepLinkIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(deepLinkIntent)
            }

            PushClickBehavior.ACTIVITY_NO_FLAGS -> {
                startActivity(deepLinkIntent)
            }
        }
    }

    companion object {
        const val NOTIFICATION_PAYLOAD_EXTRA = "CIO_NotificationPayloadExtras"
    }
}
