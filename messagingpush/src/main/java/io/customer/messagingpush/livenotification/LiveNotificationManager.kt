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
import org.json.JSONArray
import org.json.JSONObject

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
        renderLocally(buildBundle(activityId, activityType, fields))
        reportStart(activityId, activityType, fields)
    }

    private fun buildBundle(activityId: String, activityType: String, fields: Map<String, Any?>): Bundle =
        Bundle().apply {
            // Write template fields first so the reserved envelope keys below always
            // win if a field key collides with one (e.g. a field named "timestamp").
            for ((key, value) in fields) {
                if (value != null) putString(key, value.toString())
            }
            putString(LiveNotificationHandler.ACTIVITY_ID_KEY, activityId)
            putString(LiveNotificationHandler.EVENT_KEY, EVENT_START)
            putString(LiveNotificationHandler.ACTIVITY_TYPE_KEY, activityType)
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

    /**
     * Coerces the flattened template [fields] into values the data pipeline can
     * serialize: `org.json` containers (e.g. FlightStatus' `origin`) become
     * plain [Map]/[List], and null entries are dropped.
     */
    private fun Map<String, Any?>.toJsonSafePayload(): Map<String, Any?> =
        buildMap {
            for ((key, value) in this@toJsonSafePayload) {
                if (value != null) put(key, value.toJsonSafe())
            }
        }

    private fun Any?.toJsonSafe(): Any? = when (this) {
        null -> null
        is JSONObject -> buildMap<String, Any?> {
            for (key in keys()) put(key, opt(key)?.takeIf { it != JSONObject.NULL }?.toJsonSafe())
        }
        is JSONArray -> (0 until length()).map { opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toJsonSafe() }
        else -> this
    }

    companion object {
        private const val EVENT_START = "start"
        private const val FCM_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"
        private const val FCM_DEFAULT_COLOR = "com.google.firebase.messaging.default_notification_color"
    }
}
