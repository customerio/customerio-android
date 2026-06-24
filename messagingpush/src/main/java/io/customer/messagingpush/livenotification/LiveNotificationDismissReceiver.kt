package io.customer.messagingpush.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.liveNotificationLifecycleClient
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent

/**
 * Receives the delete intent the system fires when the user dismisses a live
 * notification, and reports an `end` event to Customer.io.
 *
 * Programmatic [android.app.NotificationManager.cancel] does NOT trigger a
 * delete intent, so server-driven `end` events (which cancel the notification)
 * do not produce a false user-dismissal report. The `instanceUUID`/`activityType`
 * ride on the intent extras (attached when the notification was shown); the
 * `deviceId` is the current FCM token, read here.
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
        // Internal: only the SDK sets these (on the delete PendingIntent) and reads them here.
        internal const val EXTRA_ACTIVITY_ID = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_ID"
        internal const val EXTRA_ACTIVITY_TYPE = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_TYPE"
    }
}
