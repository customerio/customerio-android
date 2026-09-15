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
import io.customer.messagingpush.livenotification.LiveNotificationBranding
import io.customer.messagingpush.livenotification.LiveNotificationBrandingSerializer
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

    /**
     * Renders and posts the live notification described by the bundle.
     *
     * Every failure is contained here rather than at the call sites, because both callers
     * run somewhere a throw is fatal: the FCM path executes on
     * `FirebaseMessagingService.onMessageReceived`, and the local path on an SDK-owned
     * coroutine scope with no exception handler. The risky work — template rendering, asset
     * decoding/download, the host app's
     * [io.customer.messagingpush.data.communication.CustomerIOLiveNotificationsCallback], and
     * the `notify` call itself — is all inside. A failure drops this render only.
     */
    fun handle(
        context: Context,
        // Null for locally-started activities: they were never delivered by Customer.io, so
        // there is nothing to attribute a delivery metric to. The public
        // [CustomerIOParsedPushPayload] types these as non-null, so they are flattened to ""
        // when the payload is built below, and the click path skips metric reporting on blank.
        deliveryId: String?,
        deliveryToken: String?,
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
        // can't post or re-store a previous user's activity. Supplied for every render that
        // runs on LiveNotificationManager's render chain — local and server-delivered alike.
        isSuperseded: () -> Boolean = { false }
    ) {
        runCatching {
            handleInternal(
                context = context,
                deliveryId = deliveryId,
                deliveryToken = deliveryToken,
                smallIcon = smallIcon,
                tintColor = tintColor,
                channelId = channelId,
                notificationManager = notificationManager,
                bypassOrderGuard = bypassOrderGuard,
                isSuperseded = isSuperseded
            )
        }.onFailure { cause ->
            val activityId = bundle.getString(CIO_INSTANCE_ID_KEY)
            SDKComponent.logger.error(
                "Failed to render live notification '$activityId': ${cause.message}"
            )
            // An `end` claims the terminal transition in the store *before* the render runs —
            // server pushes in the order guard below, local ends in LiveNotificationManager.end.
            // So a failure here would leave the previous ongoing (non-dismissible) notification on
            // screen while every retry `end` is dropped by that same claim. Cancel it on both
            // paths so a failed render can't strand the activity visible and unclearable.
            if (bundle.getString(EVENT_KEY) == EVENT_END && activityId != null) {
                notificationManager.cancel(activityId, notificationId(activityId))
            }
        }
    }

    @Suppress("LongParameterList")
    private fun handleInternal(
        context: Context,
        deliveryId: String?,
        deliveryToken: String?,
        @DrawableRes smallIcon: Int,
        @ColorInt tintColor: Int?,
        channelId: String,
        notificationManager: NotificationManager,
        bypassOrderGuard: Boolean,
        isSuperseded: () -> Boolean
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
        val enabledTypes = enabledActivityTypes()
        if (activityType == null || activityType !in enabledTypes) {
            // Escalated from debug: reaching here means Customer.io delivered a live notification
            // for a type this app doesn't render, which is a misconfiguration the customer needs to
            // see. error is the only level visible at the SDK's default log level.
            SDKComponent.logger.error(
                if (enabledTypes.isEmpty()) {
                    "Live notification type '$activityType' is not enabled; ignoring activity " +
                        "'$activityId'. This app has never enabled live notifications — enable the " +
                        "type via MessagingPushModuleConfig if it should render."
                } else {
                    "Live notification type '$activityType' is not enabled; ignoring activity " +
                        "'$activityId'. Enabled types are $enabledTypes."
                }
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
        } else if (!isEnd && store.isEnded(activityId)) {
            // Local start/update renders are queued, so the activity can go terminal between
            // enqueue and render — most commonly a user swipe, which the dismiss receiver marks
            // ended. Without re-checking here the render would repost a notification the user
            // already cleared. `end` is exempt: it renders precisely because it just went terminal.
            SDKComponent.logger.debug(
                "Dropping local render for ended live notification '$activityId'."
            )
            return
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
        val branding = branding(context)
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
            // Same supersede gate as the post path below: a render invalidated by a logout
            // must not touch the store the reset just cleared, on any exit path.
            if (isSuperseded()) {
                SDKComponent.logger.debug(
                    "Live notification render for '$activityId' was superseded by a reset; dropping."
                )
                return
            }
            advanceHighWaterMark()
            notificationManager.cancel(activityId, notifId)
            return
        }

        bundle.putInt(CustomerIOPushNotificationHandler.NOTIFICATION_REQUEST_CODE, notifId)
        val parsedPayload = CustomerIOParsedPushPayload(
            extras = flattenedExtras(bundle, data),
            deepLink = result?.deepLink ?: bundle.getString(CustomerIOPushNotificationHandler.DEEP_LINK_KEY),
            cioDeliveryId = deliveryId.orEmpty(),
            cioDeliveryToken = deliveryToken.orEmpty(),
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
            ?.withDefaultIntents(pendingIntent, deletePendingIntent)
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

        // Re-check terminal state immediately before committing, on both paths. The guard above
        // runs before the template render and the app callback, either of which can block for a
        // long time — a remote branding logo waits up to ~20s. A user swipe during that work has
        // the dismiss receiver mark the activity ended, and posting now would resurrect a
        // notification they already cleared. `end` is exempt: it posts precisely because it just
        // went terminal.
        if (!isEnd && store.isEnded(activityId)) {
            SDKComponent.logger.debug(
                "Live notification '$activityId' was ended while rendering; not posting."
            )
            return
        }

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

        // Only now, past every early return above: advancing on a render that showed nothing
        // would let the out-of-order guard drop a later push carrying an older timestamp even
        // though this one never reached the shade.
        advanceHighWaterMark()

        // Pushes are server-initiated, so the handler never reports a lifecycle event.
        // The terminal marker was already claimed above (remote) or by the manager
        // (local); the tracked activity type is intentionally kept (not cleared) so a
        // subsequent logout can still cancel an ended-but-still-visible notification.
    }

    /**
     * The activity types this app renders, falling back to the persisted opt-in when the module
     * config carries none.
     *
     * A cold process — one Android started solely to deliver an FCM message — never runs
     * `CustomerIO.initialize`, so the push module isn't registered and [pushModuleConfig] resolves
     * to its defaults. The resulting empty set is ambiguous: it means both "this app never enabled
     * live notifications" and "this app did, but this process hasn't loaded that yet". The persisted
     * copy tells them apart, so a push delivered after ordinary process death still renders.
     *
     * A populated config always wins, so *disabling* a type takes effect immediately in a running
     * process rather than waiting for the persisted copy to be rewritten, and an app that never
     * enabled the feature keeps ignoring these pushes.
     */
    private fun enabledActivityTypes(): Set<String> =
        SDKComponent.pushModuleConfig.liveNotificationTypes
            .ifEmpty { SDKComponent.liveNotificationStore.enabledActivityTypes() }

    /**
     * Branding for this render, falling back to the persisted copy in a cold process for the same
     * reason as [enabledActivityTypes] — otherwise a cold render would drop the branded small icon
     * and logo, visibly changing an ongoing notification a warm process had already posted.
     */
    private fun branding(context: Context): LiveNotificationBranding? =
        SDKComponent.pushModuleConfig.liveNotificationBranding
            ?: SDKComponent.liveNotificationStore.brandingJson()
                ?.let { LiveNotificationBrandingSerializer.decode(context, it) }

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
    /**
     * Fills in the SDK's click and dismiss intents on an app-rendered notification that didn't
     * set its own. The SDK still owns posting and lifecycle for these, so without this a custom
     * notification could not open the app, report `opened`, deep-link, or report a user swipe —
     * and every host would have to reconstruct internal receiver details to get them. An app that
     * does set either intent keeps it.
     */
    private fun Notification.withDefaultIntents(
        clickIntent: PendingIntent?,
        dismissIntent: PendingIntent?
    ): Notification = apply {
        if (contentIntent == null) contentIntent = clickIntent
        if (deleteIntent == null) deleteIntent = dismissIntent
    }

    /**
     * The bundle a host callback receives. Server pushes carry customer content nested in the
     * JSON `payload` field, which [extractData] unwraps for built-in templates; without the same
     * unwrapping here an app-rendered type would see `payload` instead of its own fields, which
     * is not what [io.customer.messagingpush.data.communication.CustomerIOLiveNotificationsCallback]
     * promises. Envelope keys already in the bundle win, so nothing the SDK owns is overwritten.
     */
    private fun flattenedExtras(bundle: Bundle, data: JSONObject): Bundle = Bundle(bundle).apply {
        remove(PAYLOAD_KEY)
        for (key in data.keys()) {
            if (containsKey(key)) continue
            val value = data.opt(key) ?: continue
            putString(key, value.toString())
        }
    }

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
