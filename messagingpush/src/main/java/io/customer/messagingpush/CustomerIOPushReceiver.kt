package io.customer.messagingpush

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder
import io.customer.messagingpush.CustomerIOPushNotificationHandler.Companion.NOTIFICATION_REQUEST_CODE
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.util.PushTrackingUtilImpl.Companion.DELIVERY_TOKEN_KEY

internal class CustomerIOPushReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "io.customer.messagingpush.PUSH_ACTION"
        const val PUSH_PAYLOAD_KEY = "CIO-Push-Payload"
    }

    private val diGraph: CustomerIOComponent
        get() = CustomerIO.instance().diGraph

    private val moduleConfig: MessagingPushModuleConfig
        get() = diGraph.moduleConfig

    private val deepLinkUtil: DeepLinkUtil
        get() = diGraph.deepLinkUtil

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }

        // Dismiss the notification
        val requestCode = intent.getIntExtra(NOTIFICATION_REQUEST_CODE, 0)
        val mNotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.cancel(requestCode)

        val payload: CustomerIOParsedPushPayload? = intent.extras?.getParcelable(PUSH_PAYLOAD_KEY)
        val deliveryId = payload?.cioDeliveryId
        val deliveryToken = payload?.extras?.getString(DELIVERY_TOKEN_KEY)

        if (deliveryId != null && deliveryToken != null) {
            CustomerIO.instance().trackMetric(deliveryId, MetricEvent.opened, deliveryToken)
        }

        if (payload != null) {
            handleDeepLink(context, payload)
        }
    }

    private fun handleDeepLink(context: Context, payload: CustomerIOParsedPushPayload) {
        // check if host app overrides the handling of deeplink
        val deepLinkIntents = moduleConfig.notificationCallback?.createIntentsForLink(payload)
            // check if the deep links are handled within the host app
            ?: deepLinkUtil.createDefaultDeepLinkHandlerIntents(context, payload.deepLink)

        if (!deepLinkIntents.isNullOrEmpty()) {
            TaskStackBuilder.create(context).run {
                deepLinkIntents.forEach { addNextIntentWithParentStack(it) }
                startActivities()
            }
        }
    }
}
