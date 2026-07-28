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
 * corresponding start/end lifecycle events to Customer.io. Local updates are
 * rendered but intentionally not reported — only start/end emit CDP events.
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
    //
    // Entries are dropped by end() and by logout, but not by a remote end or a user
    // swipe, so an activity finished either of those ways keeps its bundle until the
    // next logout or process death. That is a few hundred bytes per activity against
    // a count bounded by how many live notifications one app start actually shows, so
    // it is left unbounded rather than adding eviction that could discard the terminal
    // state a later end() wants to render.
    private val lastBundles = ConcurrentHashMap<String, Bundle>()

    private val renderScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + dispatchers.background)
    }

    // Renders are chained so each waits for the previous one to finish, keeping
    // them in submission order — a slow earlier render (e.g. a RemoteUrl logo
    // download) can't complete after, and overwrite, a later one.
    private var renderChain: Job = Job().also { it.complete() }

    // Bumped on reset. A render enqueued before a logout captures the current
    // generation; if it changed by the time the render runs, the render is dropped
    // so it can't re-post a previous user's activity into a just-cleared store.
    @Volatile
    private var renderGeneration = 0

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
        // Lifecycle reporting is intentionally decoupled from the local render: the
        // app called start/end, so Customer.io must track the activity (and be
        // able to push updates / remote-end it) even when the local render posts nothing
        // — e.g. a custom type with no built-in template, or a transient render failure.
        // The report is not blocking (token read is an in-memory pref; track() enqueues),
        // so it stays on the caller thread; identity gating still applies in the client.
        reportStart(activityId, activityType, attributes, contentState)
    }

    /**
     * Re-renders a previously started live notification locally. The update is
     * intentionally not reported to Customer.io — only start/end emit CDP events.
     */
    fun update(
        activityId: String,
        activityType: String,
        attributes: Map<String, Any?>,
        contentState: Map<String, Any?>
    ) {
        // Terminal state is governed here for local renders (they bypass the handler's
        // server-push guard): an update after the activity ended locally or was dismissed
        // must not repost it. `start` mints a fresh id, and `end` guards itself, so only
        // `update` needs this check.
        if (SDKComponent.liveNotificationStore.isEnded(activityId)) {
            SDKComponent.logger.debug(
                "Live notification '$activityId' already ended; ignoring update."
            )
            return
        }
        val bundle = buildBundle(activityId, activityType, attributes + contentState, EVENT_UPDATE)
        lastBundles[activityId] = Bundle(bundle)
        render(bundle)
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
        // Already terminal (e.g. the user dismissed it, or end was already called):
        // end is idempotent per id, so don't re-render a dismissed notification or
        // report a second end.
        if (store.isEnded(activityId)) {
            SDKComponent.logger.debug(
                "Live notification '$activityId' already ended; nothing to do."
            )
            return
        }
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
        } else {
            // No cached content to render a terminal state (e.g. after process death):
            // cancel so a previously ongoing notification isn't left stuck and non-dismissible.
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(activityId, LiveNotificationHandler.notificationId(activityId))
        }

        // Claim the terminal transition so `end` is reported at most once per id
        // (a prior user swipe-dismiss may have already reported it). The store's
        // timestamp/type are intentionally NOT cleared: the terminal marker and the
        // high-water mark must survive so a delayed older push can't resurrect the
        // activity, and logout can still cancel a still-visible ended notification.
        // Reclamation happens via the store's TTL trim / logout clear.
        if (activityType == null) {
            SDKComponent.logger.debug(
                "No known live notification for '$activityId'; nothing to end."
            )
        } else if (store.markEnded(activityId)) {
            reportEnd(activityId, activityType)
        }
    }

    /**
     * Cancels every tracked live notification and clears their stored state,
     * without reporting `end` events. Called on logout (reset).
     */
    @Synchronized
    fun cancelAllActivities() {
        // Invalidate any render queued before this reset (see render()) so a
        // start()'s enqueued work can't re-add a previous user's activity after the
        // store is cleared below.
        renderGeneration++
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
        enqueueRender(bundle.getString(LiveNotificationHandler.CIO_INSTANCE_ID_KEY)) { isSuperseded ->
            renderLocally(bundle, isSuperseded)
        }
    }

    /**
     * Renders a server-delivered live notification off the caller's thread.
     *
     * Push renders used to run inline on Firebase's `onMessageReceived` thread, where the
     * blocking branding-logo download (up to ~20s) held up Firebase's message handler and
     * delayed follow-up pushes. Routing them through the same chain as local renders releases
     * that thread immediately and keeps push and local renders from interleaving.
     */
    @Synchronized
    fun renderFromPush(activityId: String?, render: (isSuperseded: () -> Boolean) -> Unit) {
        enqueueRender(activityId, render)
    }

    /**
     * Queues [render] behind any in-flight render so the two can't interleave, and drops it if a
     * logout/reset lands first.
     */
    private fun enqueueRender(activityId: String?, render: (isSuperseded: () -> Boolean) -> Unit) {
        val generation = renderGeneration
        val previous = renderChain
        renderChain = renderScope.launch {
            previous.join()
            // A logout/reset after this render was queued invalidates it, so it can't
            // re-post a previous user's activity into a store that reset just cleared.
            if (generation != renderGeneration) return@launch
            // The render below performs a blocking branding-logo download (up to ~20s) and
            // may invoke a slow app renderer. A logout can land during that window — after
            // this pre-check passed — so the handler re-checks the generation immediately
            // before it posts the notification and writes the activity type back.
            //
            // LiveNotificationHandler.handle contains its own failures, so this covers the
            // setup around it (metadata lookup, channel creation, system service). Needed
            // because this coroutine runs on an SDK-owned scope with no exception handler,
            // where anything escaping would take the host process down from a thread the app
            // cannot guard. Drop the render and keep the chain alive instead.
            runCatching {
                render { generation != renderGeneration }
            }.onFailure { cause ->
                SDKComponent.logger.error(
                    "Failed to render live notification '$activityId': ${cause.message}"
                )
            }
        }
    }

    private fun renderLocally(bundle: Bundle, isSuperseded: () -> Boolean) {
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
            // Locally started: no Customer.io delivery to attribute metrics to.
            deliveryId = null,
            deliveryToken = null,
            smallIcon = smallIcon,
            tintColor = tintColor,
            channelId = channelId,
            notificationManager = notificationManager,
            bypassOrderGuard = true,
            isSuperseded = isSuperseded
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
