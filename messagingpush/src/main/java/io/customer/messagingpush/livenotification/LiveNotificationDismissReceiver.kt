package io.customer.messagingpush.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.liveNotificationLifecycleClient
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

/**
 * Receives the delete intent fired when the user dismisses a live notification
 * and reports an `end` event to Customer.io.
 */
class LiveNotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return
        val activityType = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: return
        SDKComponent.setupAndroidComponent(context = context)

        val deviceId = SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (deviceId.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available; skipping end event for live notification '$activityId'."
            )
            return
        }
        SDKComponent.liveNotificationLifecycleClient.reportEnd(
            instanceUUID = activityId,
            activityType = activityType,
            deviceId = deviceId
        )
    }

    internal companion object {
        internal const val EXTRA_ACTIVITY_ID = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_ID"
        internal const val EXTRA_ACTIVITY_TYPE = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_TYPE"
    }
}
