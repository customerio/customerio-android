package io.customer.messagingpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.moduleConfig
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.sdk.CustomerIO

class CustomerIOFirebaseMessagingReceiver : BroadcastReceiver() {
    private fun getSDKInstanceOrNull(context: Context): CustomerIO? {
        return CustomerIO.instanceOrNull(context, listOf(ModuleMessagingPushFCM()))
    }

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val sdkInstance = getSDKInstanceOrNull(context) ?: return

        val moduleConfig = sdkInstance.diGraph.moduleConfig
        val pushTrackingUtil = sdkInstance.diGraph.pushTrackingUtil

        // Track delivered event only if auto-tracking is enabled
        if (moduleConfig.autoTrackPushEvents) {
            pushTrackingUtil.parseIntentExtrasForTrackingDelivered(extras)
        }
    }
}
