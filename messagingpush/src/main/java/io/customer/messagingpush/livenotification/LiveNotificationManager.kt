package io.customer.messagingpush.livenotification

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.customer.messagingpush.LiveNotificationHandler
import io.customer.messagingpush.extensions.getColorOrNull
import io.customer.messagingpush.extensions.getMetaDataResource
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.applicationMetaData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Starts a live notification locally on behalf of the host app: renders it
 * immediately through the normal templating path, then registers the instance
 * with the backend so Customer.io can push subsequent updates to it.
 *
 * Rendering goes straight through [LiveNotificationHandler] (not the FCM
 * delivery path), so no delivered/opened metric is fabricated for a
 * locally-started notification.
 */
internal class LiveNotificationManager(
    private val lifecycleClient: LiveNotificationLifecycleClient,
    private val registrar: LiveNotificationRegistrar,
    private val notificationChannelCreator: NotificationChannelCreator = NotificationChannelCreator()
) {
    private val context: Context
        get() = SDKComponent.android().applicationContext

    fun start(activityId: String, activityType: String, fields: Map<String, Any?>) {
        renderLocally(buildBundle(activityId, activityType, fields))
        registerInstance(activityId, activityType)
    }

    private fun buildBundle(activityId: String, activityType: String, fields: Map<String, Any?>): Bundle =
        Bundle().apply {
            putString(LiveNotificationHandler.ACTIVITY_ID_KEY, activityId)
            putString(LiveNotificationHandler.EVENT_KEY, EVENT_START)
            putString(LiveNotificationHandler.ACTIVITY_TYPE_KEY, activityType)
            putString(LiveNotificationHandler.TIMESTAMP_KEY, System.currentTimeMillis().toString())
            for ((key, value) in fields) {
                if (value != null) putString(key, value.toString())
            }
        }

    private fun renderLocally(bundle: Bundle) {
        val ctx = context
        val appMetaData = ctx.applicationMetaData()
        val applicationName = ctx.applicationInfo.loadLabel(ctx.packageManager).toString()

        @DrawableRes
        val smallIcon = appMetaData?.getMetaDataResource(FCM_DEFAULT_ICON) ?: ctx.applicationInfo.icon

        @ColorInt
        val tintColor = appMetaData?.getMetaDataResource(FCM_DEFAULT_COLOR)?.let { ctx.getColorOrNull(it) }

        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = notificationChannelCreator.createLiveNotificationChannelIfNeededAndReturnChannelId(
            context = ctx,
            applicationName = applicationName,
            appMetaData = appMetaData,
            notificationManager = notificationManager
        )

        LiveNotificationHandler(bundle).handle(
            context = ctx,
            deliveryId = "",
            deliveryToken = "",
            smallIcon = smallIcon,
            tintColor = tintColor,
            channelId = channelId,
            notificationManager = notificationManager
        )
    }

    private fun registerInstance(activityId: String, activityType: String) {
        // Reuse the token the registrar already tracks (fetched once in ModuleMessagingPushFCM)
        // instead of requesting it again.
        val token = registrar.currentToken()
        if (token == null) {
            SDKComponent.logger.debug(
                "No FCM token available yet; skipping instance registration for live notification '$activityId'."
            )
            return
        }
        CoroutineScope(SDKComponent.dispatchersProvider.background).launch {
            lifecycleClient.registerInstance(activityId, activityType, token, registrar.currentUserId())
        }
    }

    companion object {
        private const val EVENT_START = "start"
        private const val FCM_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"
        private const val FCM_DEFAULT_COLOR = "com.google.firebase.messaging.default_notification_color"
    }
}
