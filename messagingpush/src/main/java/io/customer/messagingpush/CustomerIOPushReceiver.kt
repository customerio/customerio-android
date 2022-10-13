package io.customer.messagingpush

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.CustomerIOPushNotificationHandler.Companion.NOTIFICATION_REQUEST_CODE
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOShared
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.util.Logger

internal class CustomerIOPushReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "io.customer.messagingpush.PUSH_ACTION"
        const val PUSH_PAYLOAD_KEY = "CIO-Push-Payload"
    }

    private val logger: Logger
        get() = CustomerIOShared.instance().diStaticGraph.logger

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        val diGraph: CustomerIOComponent? = CustomerIO.instanceOrNull(context)?.diGraph
        val moduleConfig: MessagingPushModuleConfig? = diGraph?.moduleConfig

        // Dismiss the notification
        val requestCode = intent.getIntExtra(NOTIFICATION_REQUEST_CODE, 0)
        val mNotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.cancel(requestCode)

        val payload: CustomerIOParsedPushPayload? = intent.extras?.getParcelable(PUSH_PAYLOAD_KEY)
        val deliveryId = payload?.cioDeliveryId
        val deliveryToken = payload?.cioDeliveryToken

        if (deliveryId != null && deliveryToken != null && moduleConfig?.autoTrackPushEvents != false) {
            CustomerIO.instance().trackMetric(deliveryId, MetricEvent.opened, deliveryToken)
        }

        if (payload != null) {
            handleDeepLink(context, payload)
        }
    }

    private fun handleDeepLink(context: Context, payload: CustomerIOParsedPushPayload) {
        // check if host app overrides the handling of deeplink
        val diGraph: CustomerIOComponent? = CustomerIO.instanceOrNull(context)?.diGraph
        val moduleConfig: MessagingPushModuleConfig? = diGraph?.moduleConfig

        val deepLinkUtil: DeepLinkUtil? = diGraph?.deepLinkUtil

        val taskStackBuilder =
            moduleConfig?.notificationCallback?.createTaskStackFromPayload(context, payload)
        if (taskStackBuilder != null) {
            taskStackBuilder.startActivities()
            return
        }

        val deepLinkIntent = deepLinkUtil?.createDeepLinkHostAppIntent(
            // check if the deep links are handled within the host app
            context,
            payload.deepLink
        ) ?: payload.deepLink?.let { link ->
            // check if the deep links can be opened outside the host app
            deepLinkUtil?.createDeepLinkExternalIntent(
                context = context,
                link = link,
                startingFromService = true
            )
        } ?: deepLinkUtil?.createDefaultHostAppIntent(
            context = context,
            contentActionLink = null
        )

        deepLinkIntent?.let { intent ->
            try {
                context.startActivity(intent)
            } catch (ex: ActivityNotFoundException) {
                logger.error("Unable to start activity for notification action $payload; ${ex.message}")
            }
        }
    }
}
