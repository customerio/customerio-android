package io.customer.messagingpush.livenotification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.customer.messagingpush.di.liveNotificationLifecycleClient
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.setupAndroidComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Receives the delete intent the system fires when the user dismisses a live
 * notification, and reports the dismissal to the backend.
 *
 * Programmatic [android.app.NotificationManager.cancel] does NOT trigger a
 * delete intent, so server-driven `end` events (which cancel the notification)
 * do not produce a false user-dismissal report.
 */
class LiveNotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val activityId = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return
        SDKComponent.setupAndroidComponent(context = context)
        // Keep the receiver alive for the short-lived network call.
        val pendingResult = goAsync()
        CoroutineScope(SDKComponent.dispatchersProvider.background).launch {
            try {
                SDKComponent.liveNotificationLifecycleClient.reportDismissed(activityId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ACTIVITY_ID = "io.customer.messagingpush.EXTRA_LIVE_ACTIVITY_ID"
    }
}
