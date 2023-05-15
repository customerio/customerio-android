package io.customer.messagingpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.extensions.getSDKInstanceOrNull
import io.customer.sdk.data.request.MetricEvent

class CustomerIOFirebaseMessagingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        // if CustomerIO instance isn't initialized, we cannot process the notification
        val sdkInstance = context.getSDKInstanceOrNull() ?: return

        val moduleConfig = sdkInstance.diGraph.moduleConfig
        val pushTrackingUtil = sdkInstance.diGraph.pushTrackingUtil

        // Track delivered event only if auto-tracking is enabled
        if (moduleConfig.autoTrackPushEvents) {
            pushTrackingUtil.parseAndTrackMetricEvent(extras, MetricEvent.delivered)
        }
    }
}
