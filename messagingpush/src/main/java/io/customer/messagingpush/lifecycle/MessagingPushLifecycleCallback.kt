package io.customer.messagingpush.lifecycle

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import io.customer.messagingpush.MessagingPushModuleConfig
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.lifecycle.LifecycleCallback

internal class MessagingPushLifecycleCallback internal constructor(
    private val moduleConfig: MessagingPushModuleConfig,
    private val pushTrackingUtil: PushTrackingUtil
) : LifecycleCallback {
    override val eventsToObserve: List<Lifecycle.Event> = listOf(Lifecycle.Event.ON_CREATE)

    override fun onEventChanged(event: Lifecycle.Event, activity: Activity, extras: Bundle?) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> {
                val intentArguments = activity.intent.extras ?: return

                if (moduleConfig.autoTrackPushEvents) {
                    pushTrackingUtil.parseLaunchedActivityForTracking(intentArguments)
                }
            }

            else -> {}
        }
    }
}
