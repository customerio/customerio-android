package io.customer.messagingpush.livenotification

import android.content.BroadcastReceiver
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.liveNotificationLifecycleClient
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Receives the delete intent fired when the user dismisses a live notification
 * and reports an `end` event to Customer.io.
 */
class LiveNotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return
        val activityType = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: return
        SDKComponent.setupAndroidComponent(context = context)

        // Claim the terminal transition first, whether or not the end turns out to be
        // reportable: the user dismissed this notification, so nothing may repost it. Renders
        // consult this marker, so a dismissal left unmarked would let a queued local update or
        // a later push bring back a notification the user already cleared. Report `end` at most
        // once per id; if already ended (e.g. endLiveNotification ran) skip the duplicate. The
        // marker also retains the store's ordering guard for the same reason.
        if (!SDKComponent.liveNotificationStore.markEnded(activityId)) {
            SDKComponent.logger.debug(
                "Live notification '$activityId' already ended; skipping dismiss end event."
            )
            return
        }

        // Lifecycle events require a registered device token and are never retried, so a
        // dismissal without one stays local: the activity is terminal on the device, but
        // Customer.io never learns the notification was cleared.
        val deviceId = SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (deviceId.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available; skipping end event for live notification '$activityId'."
            )
            return
        }
        // Keep the process alive past onReceive() so the async CDP pipeline can
        // persist the end event before the receiver's process is torn down.
        // Only available while the framework is dispatching the broadcast — null when
        // onReceive is invoked directly, in which case there is nothing to finish.
        val pendingResult: PendingResult? = goAsync()
        CoroutineScope(SDKComponent.dispatchersProvider.background).launch {
            try {
                SDKComponent.liveNotificationLifecycleClient.reportEnd(
                    instanceUUID = activityId,
                    activityType = activityType,
                    deviceId = deviceId
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }

    internal companion object {
        internal const val EXTRA_ACTIVITY_ID = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_ID"
        internal const val EXTRA_ACTIVITY_TYPE = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_TYPE"
    }
}
