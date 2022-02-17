package io.customer.messagingpush

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.customer.messagingpush.CustomerIOPushNotificationHandler.Companion.DEEP_LINK_KEY
import io.customer.messagingpush.CustomerIOPushNotificationHandler.Companion.DELIVERY_ID
import io.customer.messagingpush.CustomerIOPushNotificationHandler.Companion.DELIVERY_TOKEN
import io.customer.messagingpush.CustomerIOPushNotificationHandler.Companion.NOTIFICATION_REQUEST_CODE
import io.customer.sdk.CustomerIO
import io.customer.sdk.data.request.MetricEvent

class CustomerIOPushReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CustomerIOPushReceiver:"
        const val ACTION = "io.customer.messagingpush.PUSH_ACTION"
    }

    override fun onReceive(context: Context?, intent: Intent?) {

        if (context == null || intent == null)
            return

        // Dismiss the notification
        val requestCode = intent.getIntExtra(NOTIFICATION_REQUEST_CODE, 0)
        val mNotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mNotificationManager.cancel(requestCode)

        val bundle = intent.extras
        val deliveryId = bundle?.getString(DELIVERY_ID)
        val deliveryToken = bundle?.getString(DELIVERY_TOKEN)

        if (deliveryId != null && deliveryToken != null) {
            CustomerIO.instance().trackMetric(deliveryId, MetricEvent.opened, deliveryToken)
                .enqueue()
        }

        val deepLink = bundle?.getString(DEEP_LINK_KEY)
        if (deepLink != null) {
            handleDeepLink(context, deepLink)
        }
    }

    private fun handleDeepLink(context: Context, deepLink: String) {
        val deepLinkUri = Uri.parse(deepLink)

        // check if host app overrides the handling of deeplink
        if (CustomerIO.instance().config.urlHandler != null) {
            if (CustomerIO.instance().config.urlHandler?.handleCustomerIOUrl(deepLinkUri) == true)
                return
        }

        // check if the deep links are handled within the host app
        val intent = Intent(Intent.ACTION_VIEW).apply { data = deepLinkUri }

        val resolveInfo = context.packageManager.queryIntentActivities(intent, 0)
        for (item in resolveInfo) {
            if (item.activityInfo.packageName == context.packageName) {
                intent.setPackage(item.activityInfo.packageName)
                break
            }
        }

        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        } else {
            Log.v(TAG, "No supporting application for this deepLink $deepLink")
            return
        }
    }
}
