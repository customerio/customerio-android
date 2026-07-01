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

/**
 * Starts a live notification locally on behalf of the host app: renders it
 * immediately through the normal templating path, then reports a `start` event
 * to Customer.io so the backend learns the activity (and the device to push
 * updates to, carried as `deviceId`).
 *
 * Rendering goes straight through [LiveNotificationHandler] (not the FCM
 * delivery path), so no delivered/opened metric is fabricated for a
 * locally-started notification.
 */
internal class LiveNotificationManager(
    private val lifecycleClient: LiveNotificationLifecycleClient,
    private val notificationChannelCreator: NotificationChannelCreator = NotificationChannelCreator()
) {
    private val context: Context
        get() = SDKComponent.android().applicationContext

    fun start(activityId: String, activityType: String, fields: Map<String, Any?>) {
        renderLocally(buildBundle(activityId, activityType, fields, EVENT_START))
        // A push-delivered `start` is backend-initiated, so the handler never reports
        // it; a local start is client-initiated, so we report it here.
        reportStart(activityId, activityType, fields)
    }

    /**
     * Updates a live notification previously started via [start] (same
     * [activityId]): re-renders it in place and reports an `update` event. This
     * is the client-initiated (on-device) update path; a push-delivered `update`
     * is backend-initiated and is rendered by [LiveNotificationHandler] without
     * being reported.
     */
    fun update(activityId: String, activityType: String, fields: Map<String, Any?>) {
        renderLocally(buildBundle(activityId, activityType, fields, EVENT_UPDATE))
        reportUpdate(activityId, activityType, fields)
    }

    private fun buildBundle(
        activityId: String,
        activityType: String,
        fields: Map<String, Any?>,
        event: String
    ): Bundle =
        Bundle().apply {
            // Write template fields first so the reserved envelope keys below always
            // win if a field key collides with one (e.g. a field named "timestamp").
            for ((key, value) in fields) {
                if (value != null) putString(key, value.toString())
            }
            putString(LiveNotificationHandler.ACTIVITY_ID_KEY, activityId)
            putString(LiveNotificationHandler.EVENT_KEY, event)
            putString(LiveNotificationHandler.NOTIFICATION_TYPE_KEY, activityType)
            putString(LiveNotificationHandler.TIMESTAMP_KEY, System.currentTimeMillis().toString())
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

    private fun reportStart(activityId: String, activityType: String, fields: Map<String, Any?>) {
        val deviceId = SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (deviceId.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available yet; skipping start event for live notification '$activityId'."
            )
            return
        }
        lifecycleClient.reportStart(
            instanceUUID = activityId,
            activityType = activityType,
            deviceId = deviceId,
            payload = fields.toJsonSafePayload()
        )
    }

    private fun reportUpdate(activityId: String, activityType: String, fields: Map<String, Any?>) {
        val deviceId = SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (deviceId.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available yet; skipping update event for live notification '$activityId'."
            )
            return
        }
        lifecycleClient.reportUpdate(
            instanceUUID = activityId,
            activityType = activityType,
            deviceId = deviceId,
            payload = fields.toJsonSafePayload()
        )
    }

    companion object {
        private const val EVENT_START = "start"
        private const val EVENT_UPDATE = "update"
        private const val FCM_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"
        private const val FCM_DEFAULT_COLOR = "com.google.firebase.messaging.default_notification_color"
    }
}
