package io.customer.messagingpush

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import io.customer.messagingpush.activity.NotificationClickReceiverActivity
import io.customer.messagingpush.data.model.CustomerIOParsedPushPayload
import io.customer.messagingpush.di.liveNotificationStore
import io.customer.messagingpush.di.pushModuleConfig
import io.customer.messagingpush.livenotification.LiveNotificationDismissReceiver
import io.customer.messagingpush.livenotification.template.TemplateAssets
import io.customer.messagingpush.livenotification.template.TemplateRegistry
import io.customer.messagingpush.livenotification.template.TemplateRenderResult
import io.customer.messagingpush.util.PushTrackingUtil
import io.customer.sdk.core.di.SDKComponent
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Dispatches templated live notifications — ongoing notifications updated
 * in-place (the Android counterpart of iOS Live Activities). Pushes sharing a
 * [CIO_INSTANCE_ID_KEY] replace the previous notification rather than stacking.
 */
internal class LiveNotificationHandler(
    private val bundle: Bundle
) {

    companion object {
        const val CIO_INSTANCE_ID_KEY = "cioInstanceId"
        const val EVENT_KEY = "event"
        const val NOTIFICATION_TYPE_KEY = "notification_type"
        const val TIMESTAMP_KEY = "timestamp"
        const val PAYLOAD_KEY = "payload"

        private const val EVENT_END = "end"

        /** Deterministic notification id for an [activityId] so successive events address the same notification. */
        internal fun notificationId(activityId: String): Int = activityId.hashCode() and 0x7FFFFFFF

        /** Envelope keys that are never template fields; everything else is flattened into the template `data`. */
        private val RESERVED_KEYS = setOf(
            CIO_INSTANCE_ID_KEY,
            EVENT_KEY,
            NOTIFICATION_TYPE_KEY,
            TIMESTAMP_KEY,
            PAYLOAD_KEY,
            PushTrackingUtil.DELIVERY_ID_KEY,
            PushTrackingUtil.DELIVERY_TOKEN_KEY
        )
    }

    fun handle(
        context: Context,
        deliveryId: String,
        deliveryToken: String,
        @DrawableRes smallIcon: Int,
        @ColorInt tintColor: Int?,
        channelId: String,
        notificationManager: NotificationManager,
        // Locally-initiated renders skip the entire server-push guard block (both the
        // out-of-order timestamp dedupe and the terminal ended check/claim). They are
        // governed by LiveNotificationManager instead, which enforces terminal state on
        // the local start/update/end paths before rendering.
        bypassOrderGuard: Boolean = false,
        // Re-checked after the (potentially slow) render work and immediately before the
        // notification is posted and the activity type is written back. Returns true when a
        // logout/reset landed during rendering, in which case the render is dropped so it
        // can't post or re-store a previous user's activity. Local renders only.
        isSuperseded: () -> Boolean = { false }
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SDKComponent.logger.error(
                "POST_NOTIFICATIONS permission not granted; live notification will be dropped by the system. " +
                    "The host app must request this permission on Android 13+."
            )
        }

        val activityId = bundle.getString(CIO_INSTANCE_ID_KEY) ?: return
        val event = bundle.getString(EVENT_KEY)
        if (event == null) {
            SDKComponent.logger.error(
                "Live notification push for activity '$activityId' is missing '$EVENT_KEY'; dropping."
            )
            return
        }

        val activityType = bundle.getString(NOTIFICATION_TYPE_KEY)
        if (activityType == null || activityType !in SDKComponent.pushModuleConfig.liveNotificationTypes) {
            SDKComponent.logger.debug(
                "Live notification type '$activityType' is not enabled; ignoring activity '$activityId'."
            )
            return
        }
        val isEnd = event == EVENT_END

        val store = SDKComponent.liveNotificationStore
        val timestamp = bundle.getString(TIMESTAMP_KEY)?.toLongOrNull()

        // Terminal / out-of-order guard for server pushes. Local renders are host-ordered
        // and governed by LiveNotificationManager, so they bypass this entirely.
        if (!bypassOrderGuard) {
            if (isEnd) {
                // Claim the terminal transition. Only the first `end` renders the terminal
                // state; a duplicate or late `end` — including one arriving after the user
                // already dismissed the notification (the dismiss receiver marks it ended) —
                // is dropped so it can't re-post a notification the user already cleared.
                if (!store.markEnded(activityId)) {
                    SDKComponent.logger.debug(
                        "Dropping duplicate/late end for already-ended live notification '$activityId'."
                    )
                    return
                }
            } else if (store.isEnded(activityId)) {
                // A non-end event for an ended id (delayed/duplicate start or update) is stale.
                SDKComponent.logger.debug("Dropping event for ended live notification '$activityId'.")
                return
            } else {
                // Services emits whole-second timestamps, so an in-order start+update can
                // share a second; reject only strictly-older pushes.
                val lastSeen = store.lastTimestamp(activityId)
                if (timestamp != null && lastSeen != null && timestamp < lastSeen) {
                    SDKComponent.logger.debug(
                        "Dropping out-of-order/duplicate live notification for '$activityId' (timestamp $timestamp < $lastSeen)."
                    )
                    return
                }
            }
        }

        // Advances the high-water mark on BOTH paths: a local render must also bump it
        // so a later delayed remote push at an intermediate timestamp can't overwrite
        // newer local content.
        //
        // Deliberately invoked only once this render is committed (past the supersede
        // check below), not up-front: a local render invalidated by a logout must not
        // write any state back into the store that reset just cleared.
        fun advanceHighWaterMark() {
            if (timestamp == null) return
            val lastSeen = store.lastTimestamp(activityId)
            if (lastSeen == null || timestamp > lastSeen) {
                store.setLastTimestamp(activityId, timestamp)
            }
        }

        val template = TemplateRegistry.find(activityType)
        val data = extractData(bundle)
        val branding = SDKComponent.pushModuleConfig.liveNotificationBranding
        // Branding overrides the status-bar icon for live notifications only.
        val effectiveSmallIcon = branding?.smallIcon ?: smallIcon

        val result = template?.render(
            context = context,
            data = data,
            branding = branding,
            smallIcon = effectiveSmallIcon,
            fallbackTintColor = tintColor
        )?.let { rendered ->
            // The brand logo fills the large-icon slot when the template didn't set one.
            val brandingLogo = branding?.logo
            if (!rendered.cancelImmediately && rendered.largeIcon == null && brandingLogo != null) {
                rendered.copy(largeIcon = TemplateAssets.toBitmap(context, brandingLogo))
            } else {
                rendered
            }
        }

        val notifId = notificationId(activityId)

        if (result?.cancelImmediately == true) {
            advanceHighWaterMark()
            notificationManager.cancel(activityId, notifId)
            return
        }

        bundle.putInt(CustomerIOPushNotificationHandler.NOTIFICATION_REQUEST_CODE, notifId)
        val parsedPayload = CustomerIOParsedPushPayload(
            extras = Bundle(bundle),
            deepLink = result?.deepLink ?: bundle.getString(CustomerIOPushNotificationHandler.DEEP_LINK_KEY),
            cioDeliveryId = deliveryId,
            cioDeliveryToken = deliveryToken,
            title = result?.title ?: bundle.getString(CustomerIOPushNotificationHandler.TITLE_KEY).orEmpty(),
            body = result?.body ?: bundle.getString(CustomerIOPushNotificationHandler.BODY_KEY).orEmpty(),
            activityId = activityId
        )
        val pendingIntent = createIntentForNotificationClick(context, notifId, parsedPayload)
        // Only in-progress notifications carry the dismiss intent (user-swipe -> end).
        // A terminal `end` notification must not, or swiping it would report a second end.
        val deletePendingIntent = if (isEnd) null else createDeleteIntent(context, notifId, activityId, activityType)

        // The host app may fully render the notification; otherwise fall back to the SDK template.
        val appNotification = SDKComponent.pushModuleConfig.liveNotificationCallback
            ?.createLiveNotification(parsedPayload, context)
        val notification = appNotification ?: result?.let {
            buildSdkNotification(context, channelId, effectiveSmallIcon, it, pendingIntent, deletePendingIntent, ongoing = !isEnd)
        }

        // A logout/reset may have landed while the branding logo downloaded or the app
        // renderer ran above. If so, drop this render: don't post the notification and don't
        // write the activity type or the high-water mark back into the store that reset
        // just cleared.
        if (isSuperseded()) {
            SDKComponent.logger.debug(
                "Live notification render for '$activityId' was superseded by a reset; dropping."
            )
            return
        }

        advanceHighWaterMark()

        when {
            notification != null -> {
                notificationManager.notify(activityId, notifId, notification)
                store.setActivityType(activityId, activityType)
            }
            isEnd -> {
                // No renderable end-state, so there's nothing to leave in the shade:
                // cancel the (previously ongoing) notification instead of stranding it.
                notificationManager.cancel(activityId, notifId)
            }
            else -> {
                val reason = if (template != null) {
                    "required content fields are missing (payload not flattened, or empty)"
                } else {
                    "no built-in template and createLiveNotification returned null"
                }
                SDKComponent.logger.error(
                    "Not posting live notification '$activityId' (type '$activityType'): $reason."
                )
                return
            }
        }

        // Pushes are server-initiated, so the handler never reports a lifecycle event.
        // The terminal marker was already claimed above (remote) or by the manager
        // (local); the tracked activity type is intentionally kept (not cleared) so a
        // subsequent logout can still cancel an ended-but-still-visible notification.
    }

    private fun buildSdkNotification(
        context: Context,
        channelId: String,
        @DrawableRes effectiveSmallIcon: Int,
        result: TemplateRenderResult,
        pendingIntent: PendingIntent,
        deletePendingIntent: PendingIntent?,
        ongoing: Boolean
    ): Notification = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA -> {
            Api36LiveNotificationBuilder.build(
                Api36LiveNotificationParams(
                    context = context,
                    channelId = channelId,
                    title = result.title,
                    body = result.body,
                    subText = result.subText,
                    smallIcon = effectiveSmallIcon,
                    accentColor = result.accentColor,
                    segments = result.segments,
                    points = result.points,
                    progress = result.progress,
                    progressMax = result.progressMax,
                    startIconRes = result.startIconRes,
                    endIconRes = result.endIconRes,
                    trackerIconRes = result.trackerIconRes,
                    pendingIntent = pendingIntent,
                    deleteIntent = deletePendingIntent,
                    countdownUntil = result.countdownUntil,
                    largeIcon = result.largeIcon,
                    showProgress = result.showProgress,
                    ongoing = ongoing
                )
            )
        }
        else -> {
            BasicNotificationBuilder.build(
                BasicNotificationParams(
                    context = context,
                    channelId = channelId,
                    title = result.title,
                    body = result.body,
                    subText = result.subText,
                    smallIcon = effectiveSmallIcon,
                    accentColor = result.accentColor,
                    colorized = result.colorized,
                    progress = result.progress,
                    progressMax = result.progressMax,
                    pendingIntent = pendingIntent,
                    deleteIntent = deletePendingIntent,
                    countdownUntil = result.countdownUntil,
                    largeIcon = result.largeIcon,
                    showProgress = result.showProgress,
                    ongoing = ongoing
                )
            )
        }
    }

    private fun createDeleteIntent(
        context: Context,
        requestCode: Int,
        activityId: String,
        activityType: String
    ): PendingIntent {
        val intent = Intent(context, LiveNotificationDismissReceiver::class.java).apply {
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_ID, activityId)
            putExtra(LiveNotificationDismissReceiver.EXTRA_ACTIVITY_TYPE, activityType)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    /**
     * Collects the template fields from the FCM envelope: top-level keys not in
     * [RESERVED_KEYS], merged with any nested `payload` object (which wins on
     * collision). JSON-object/array strings are parsed; scalars kept verbatim.
     */
    private fun extractData(bundle: Bundle): JSONObject {
        val data = JSONObject()
        for (key in bundle.keySet()) {
            if (key in RESERVED_KEYS) continue
            val raw = bundle.getString(key) ?: continue
            data.put(key, coerceJsonValue(raw))
        }
        val payload = bundle.getString(PAYLOAD_KEY)?.let { coerceJsonValue(it) as? JSONObject }
        if (payload != null) {
            for (key in payload.keys()) {
                data.put(key, payload.get(key))
            }
        }
        return data
    }

    private fun coerceJsonValue(raw: String): Any {
        val trimmed = raw.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> raw
            }
        } catch (e: JSONException) {
            raw
        }
    }

    private fun createIntentForNotificationClick(
        context: Context,
        requestCode: Int,
        payload: CustomerIOParsedPushPayload
    ): PendingIntent {
        val notifyIntent = Intent(context, NotificationClickReceiverActivity::class.java)
        notifyIntent.putExtra(NotificationClickReceiverActivity.NOTIFICATION_PAYLOAD_EXTRA, payload)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            notifyIntent,
            flags
        )
    }
}
