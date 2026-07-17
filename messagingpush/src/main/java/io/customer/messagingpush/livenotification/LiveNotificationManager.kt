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
import io.customer.sdk.core.util.DispatchersProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val dispatchers: DispatchersProvider
        get() = SDKComponent.dispatchersProvider

    // Last bundle rendered per activity id, so end() can re-render the final
    // state as a dismissible notification and resolve the activity type even if
    // the initial render never posted.
    private val lastBundles = ConcurrentHashMap<String, Bundle>()

    private val renderScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + dispatchers.background)
    }

    // Renders are chained so each waits for the previous one to finish, keeping
    // them in submission order — a slow earlier render (e.g. a RemoteUrl logo
    // download) can't complete after, and overwrite, a later one.
    private var renderChain: Job = Job().also { it.complete() }

    /** Starts a live notification locally and reports a `start` event. */
    fun start(
        activityId: String,
        activityType: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
        val bundle = buildBundle(activityId, activityType, attributes + contentState, EVENT_START)
        lastBundles[activityId] = Bundle(bundle)
        render(bundle)
        reportStart(activityId, activityType, attributes, contentState)
    }

    /** Re-renders a previously started live notification and reports an `update` event. */
    fun update(
        activityId: String,
        activityType: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
        val bundle = buildBundle(activityId, activityType, attributes + contentState, EVENT_UPDATE)
        lastBundles[activityId] = Bundle(bundle)
        render(bundle)
        reportUpdate(activityId, activityType, contentState)
    }

    /**
     * Ends a live notification locally and reports an `end` event. Rather than
     * cancelling, it re-renders the last state as a terminal (non-ongoing)
     * notification so the final state stays in the shade and is dismissible,
     * matching push-delivered end.
     */
    fun end(activityId: String) {
        val store = SDKComponent.liveNotificationStore
        val lastBundle = lastBundles.remove(activityId)
        // Prefer the type from the last local render; fall back to the persisted
        // type (e.g. after process death) so a locally-started activity always
        // reports a matching end.
        val activityType = lastBundle?.getString(LiveNotificationHandler.NOTIFICATION_TYPE_KEY)
            ?: store.activityType(activityId)

        if (lastBundle != null) {
            val endBundle = Bundle(lastBundle).apply {
                putString(LiveNotificationHandler.EVENT_KEY, EVENT_END)
                putString(LiveNotificationHandler.TIMESTAMP_KEY, (System.currentTimeMillis() / 1000).toString())
            }
            render(endBundle)
        }

        if (activityType == null) {
            SDKComponent.logger.debug(
                "No known live notification for '$activityId'; nothing to end."
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
        // Drop cached per-instance bundles so a post-logout end() can't reuse a
        // previous session's payload.
        lastBundles.clear()
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

    /**
     * Renders off the caller's thread: a `RemoteUrl` branding logo triggers a
     * blocking image download inside the handler, which must never run on the
     * caller's (possibly main) thread.
     */
    @Synchronized
    private fun render(bundle: Bundle) {
        val previous = renderChain
        renderChain = renderScope.launch {
            previous.join()
            renderLocally(bundle)
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
            notificationManager = notificationManager,
            bypassOrderGuard = true
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
        private const val EVENT_END = "end"
        private const val FCM_DEFAULT_ICON = "com.google.firebase.messaging.default_notification_icon"
        private const val FCM_DEFAULT_COLOR = "com.google.firebase.messaging.default_notification_color"
    }
}
