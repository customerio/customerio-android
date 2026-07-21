package io.customer.messagingpush.livenotification

import android.content.BroadcastReceiver
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

        val deviceId = SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (deviceId.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available; skipping end event for live notification '$activityId'."
            )
            return
        }

        // Claim the terminal transition only after confirming we can report it, so a
        // missing token doesn't mark the id terminal (losing the end and blocking any
        // later one). Report `end` at most once per id; if already ended (e.g.
        // endLiveNotification ran) skip the duplicate. Marking ended also retains the
        // store's ordering guard so a later push can't repost the dismissed notification.
        if (!SDKComponent.liveNotificationStore.markEnded(activityId)) {
            SDKComponent.logger.debug(
                "Live notification '$activityId' already ended; skipping dismiss end event."
            )
            return
        }
        // Keep the process alive past onReceive() so the async CDP pipeline can
        // persist the end event before the receiver's process is torn down.
        val pendingResult = goAsync()
        CoroutineScope(SDKComponent.dispatchersProvider.background).launch {
            try {
                SDKComponent.liveNotificationLifecycleClient.reportEnd(
                    instanceUUID = activityId,
                    activityType = activityType,
                    deviceId = deviceId
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    internal companion object {
        internal const val EXTRA_ACTIVITY_ID = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_ID"
        internal const val EXTRA_ACTIVITY_TYPE = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_TYPE"
    }
}
