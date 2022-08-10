package io.customer.messagingpush.lifecycle

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import io.customer.messagingpush.util.DeepLinkUtil
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.lifecycle.LifecycleCallback

internal class MessagingPushLifecycleCallback internal constructor(
    private val deepLinkUtil: DeepLinkUtil,
    private val pushTrackingUtil: PushTrackingUtil
) : LifecycleCallback {
    override val eventsToObserve: List<Lifecycle.Event> = listOf(Lifecycle.Event.ON_CREATE)

    override fun onEventChanged(event: Lifecycle.Event, activity: Activity, extras: Bundle?) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> {
                val intentArguments = activity.intent.extras ?: return

                pushTrackingUtil.parseLaunchedActivityForTracking(intentArguments)
                launchContentAction(
                    activity,
                    intentArguments.getString(PENDING_CONTENT_ACTION_LINK)
                )
            }
            else -> {}
        }
    }

    private fun launchContentAction(context: Context, actionLink: String?) {
        if (actionLink.isNullOrBlank()) return

        deepLinkUtil.createDeepLinkExternalIntent(
            context = context,
            link = actionLink,
            startingFromService = false
        )?.let { contentIntent ->
            context.startActivity(contentIntent)
        }
    }

    companion object {
        internal const val PENDING_CONTENT_ACTION_LINK = "CIO-Pending-Content-Action-Link"
    }
}
