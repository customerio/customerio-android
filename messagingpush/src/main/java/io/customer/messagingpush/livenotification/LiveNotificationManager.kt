package io.customer.messagingpush.livenotification

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import io.customer.messagingpush.LiveNotificationHandler
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.extensions.getColorOrNull
import io.customer.messagingpush.extensions.getMetaDataResource
import io.customer.messagingpush.util.NotificationChannelCreator
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.extensions.applicationMetaData

/**
 * Renders live notifications locally on behalf of the host app and reports the
 * corresponding start/update/end lifecycle events to Customer.io.
 */
internal class LiveNotificationManager(
    private val lifecycleClient: LiveNotificationLifecycleClient,
    private val notificationChannelCreator: NotificationChannelCreator = NotificationChannelCreator()
) {
    private val context: Context
        get() = SDKComponent.android().applicationContext

    /** Starts a live notification locally and reports a `start` event. */
    fun start(
        activityId: String,
        activityType: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
        renderLocally(buildBundle(activityId, activityType, attributes + contentState, EVENT_START))
        reportStart(activityId, activityType, attributes, contentState)
    }

    /** Re-renders a previously started live notification and reports an `update` event. */
    fun update(
        activityId: String,
        activityType: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
        renderLocally(buildBundle(activityId, activityType, attributes + contentState, EVENT_UPDATE))
        reportUpdate(activityId, activityType, contentState)
    }

    /** Removes a previously started live notification and reports an `end` event. */
    fun end(activityId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(activityId, LiveNotificationHandler.notificationId(activityId))

        val store = SDKComponent.liveNotificationStore
        val activityType = store.activityType(activityId)
        if (activityType == null) {
            SDKComponent.logger.debug(
                "No known live notification for '$activityId'; canceled without reporting an end event."
            )
        } else {
            reportEnd(activityId, activityType)
        }
        store.clearTimestamp(activityId)
        store.clearActivityType(activityId)
    }

    /**
     * Cancels every tracked live notification and clears their stored state,
     * without reporting `end` events. Called on logout (reset).
     */
    fun cancelAllActivities() {
        val store = SDKComponent.liveNotificationStore
        val ids = store.trackedActivityIds()
        if (ids.isNotEmpty()) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            for (activityId in ids) {
                notificationManager.cancel(activityId, LiveNotificationHandler.notificationId(activityId))
            }
        }
        store.clearAllActivities()
    }

    private fun buildBundle(
        activityId: String,
        activityType: String,
        fields: Map<String, Any?>,
        event: String
    ): Bundle =
        Bundle().apply {
            // Write template fields first so the reserved envelope keys below win on collision.
            for ((key, value) in fields) {
                if (value != null) putString(key, value.toString())
            }
            putString(LiveNotificationHandler.CIO_INSTANCE_ID_KEY, activityId)
            putString(LiveNotificationHandler.EVENT_KEY, event)
            putString(LiveNotificationHandler.NOTIFICATION_TYPE_KEY, activityType)
            // Epoch seconds, matching the backend push wire contract.
            putString(LiveNotificationHandler.TIMESTAMP_KEY, (System.currentTimeMillis() / 1000).toString())
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

    private fun reportStart(
        activityId: String,
        activityType: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
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
            attributes = attributes.toJsonSafePayload(),
            contentState = contentState.toJsonSafePayload()
        )
    }

    private fun reportUpdate(activityId: String, activityType: String, contentState: Map<String, Any?>) {
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
            contentState = contentState.toJsonSafePayload()
        )
    }

    private fun reportEnd(activityId: String, activityType: String) {
        val deviceId = SDKComponent.android().globalPreferenceStore.getDeviceToken()
        if (deviceId.isNullOrBlank()) {
            SDKComponent.logger.debug(
                "No FCM token available yet; skipping end event for live notification '$activityId'."
            )
            return
        }
        lifecycleClient.reportEnd(
            instanceUUID = activityId,
            activityType = activityType,
            deviceId = deviceId
        )
    }

    companion object {
        private const val EVENT_START = "start"
        private const val EVENT_UPDATE = "update"
        private const val FCM_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"
        private const val FCM_DEFAULT_COLOR = "com.google.firebase.messaging.default_notification_color"
    }
}
