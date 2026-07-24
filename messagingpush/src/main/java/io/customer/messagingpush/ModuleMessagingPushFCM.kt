package io.customer.messagingpush

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.liveNotificationManager
import io.customer.messagingpush.di.liveNotificationRegistrar
import io.customer.messagingpush.di.pushDeliveryFlusher
import io.customer.messagingpush.di.pushLogger
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.livenotification.LiveNotificationData
import io.customer.messagingpush.livenotification.ULID
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.data.store.PendingDeliveryFlusher
import io.customer.sdk.events.Metric
import kotlinx.coroutines.flow.filter

class ModuleMessagingPushFCM @JvmOverloads constructor(
    override val moduleConfig: MessagingPushModuleConfig = MessagingPushModuleConfig.default()
) : CustomerIOModule<MessagingPushModuleConfig> {

    private val fcmTokenProvider: DeviceTokenProvider
        get() = SDKComponent.android().fcmTokenProvider
    private val pushTrackingUtil = SDKComponent.pushTrackingUtil
    private val activityLifecycleCallbacks = SDKComponent.activityLifecycleCallbacks
    private val pushDeliveryFlusher: PendingDeliveryFlusher<PendingPushDeliveryMetric>
        get() = SDKComponent.pushDeliveryFlusher
    private val pushLogger: PushNotificationLogger
        get() = SDKComponent.pushLogger

    override val moduleName: String
        get() = MODULE_NAME

    override fun initialize() {
        // Live notifications are opt-in; start before requesting the token so the
        // registrar observes the resulting RegisterDeviceTokenEvent.
        if (moduleConfig.liveNotificationTypes.isNotEmpty()) {
            SDKComponent.liveNotificationRegistrar.start()
        }
        getCurrentFcmToken()
        subscribeToLifecycleEvents()
        observeProcessForeground()
    }

    /**
     * Starts a live notification locally for a built-in template type. The SDK
     * generates a unique activity id, renders the notification immediately, and
     * registers the instance with Customer.io so the backend can push updates.
     *
     * The notification renders regardless of identity, but its lifecycle events
     * (start/end) are only reported to Customer.io for an **identified
     * user** — call `identify` first if you need the backend to track this
     * activity and push updates/remote end (matches iOS Live Activities).
     *
     * @return the generated `activity_id`, used to correlate subsequent updates.
     */
    fun startLiveNotification(data: LiveNotificationData): String {
        val activityId = ULID.generate()
        SDKComponent.liveNotificationManager.start(
            activityId = activityId,
            activityType = data.activityType,
            attributes = data.attributes(),
            contentState = data.contentState()
        )
        return activityId
    }

    /**
     * Starts a live notification locally for a customer-defined [activityType]
     * (one enabled via [MessagingPushModuleConfig.Builder.enableCustomLiveNotificationTypes]).
     * Custom types have no built-in template, so a
     * [io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback.createLiveNotification]
     * must render them.
     *
     * As with the templated overload, lifecycle events are reported to
     * Customer.io only for an identified user.
     *
     * @param data flattened fields delivered to the renderer.
     * @return the generated `activity_id`.
     */
    fun startLiveNotification(activityType: String, data: Map<String, Any?>): String {
        val activityId = ULID.generate()
        SDKComponent.liveNotificationManager.start(
            activityId = activityId,
            activityType = activityType,
            attributes = emptyMap(),
            contentState = data
        )
        return activityId
    }

    /**
     * Updates a live notification previously started via [startLiveNotification]
     * for a built-in template type: re-renders it in place. The update is
     * intentionally not reported to Customer.io — only start/end emit CDP events.
     *
     * @param activityId the id returned by [startLiveNotification].
     */
    fun updateLiveNotification(activityId: String, data: LiveNotificationData) =
        SDKComponent.liveNotificationManager.update(
            activityId = activityId,
            activityType = data.activityType,
            attributes = data.attributes(),
            contentState = data.contentState()
        )

    /**
     * Updates a live notification previously started via [startLiveNotification]
     * for a customer-defined [activityType].
     *
     * @param activityId the id returned by [startLiveNotification].
     * @param data flattened fields delivered to the renderer.
     */
    fun updateLiveNotification(activityId: String, activityType: String, data: Map<String, Any?>) {
        SDKComponent.liveNotificationManager.update(
            activityId = activityId,
            activityType = activityType,
            attributes = emptyMap(),
            contentState = data
        )
    }

    /**
     * Ends a live notification previously started via [startLiveNotification]:
     * removes it and reports an `end` event. Only the [activityId] returned by
     * [startLiveNotification] is needed — the SDK remembers the activity type.
     *
     * @param activityId the id returned by [startLiveNotification].
     */
    fun endLiveNotification(activityId: String) {
        SDKComponent.liveNotificationManager.end(activityId)
    }

    private fun subscribeToLifecycleEvents() {
        activityLifecycleCallbacks.subscribe { events ->
            events
                .filter { state ->
                    state.event == Lifecycle.Event.ON_CREATE
                }.collect { state ->
                    when (state.event) {
                        Lifecycle.Event.ON_CREATE -> runCatching {
                            val intentArguments = state.activity.get()?.intent?.extras ?: return@collect

                            if (moduleConfig.autoTrackPushEvents) {
                                pushTrackingUtil.parseLaunchedActivityForTracking(intentArguments)
                            }
                        }

                        else -> {}
                    }
                }
        }
    }

    /**
     * Register a process-wide foreground listener. The push-delivery handoff
     * fires on every foreground transition: WorkManager + direct HTTP is the
     * only credible channel in FCM-woken background processes, but once the
     * user opens the app the analytics pipeline (with foreground network and
     * full Segment storage) becomes the better channel. Any entry still in
     * the pending store at that point is handed off and its WorkManager job
     * is cancelled so the two channels can't both deliver.
     *
     * ProcessLifecycleOwner is process-scoped, so observer state is held in
     * the companion object. A repeat `initialize()` removes the prior
     * observer before installing a new one — otherwise observers would
     * accumulate and each ON_START would fire the handoff once per call to
     * `initialize()`.
     *
     * ProcessLifecycleOwner.addObserver must be called on the main thread —
     * if initialize() is invoked off-main, we post to the main looper first.
     */
    private fun observeProcessForeground() {
        val newObserver = LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
            if (event == Lifecycle.Event.ON_START) {
                handoffPendingPushDeliveryToAnalyticsPipeline()
            }
        }
        val attach = Runnable {
            val processLifecycle = ProcessLifecycleOwner.get().lifecycle
            foregroundObserver?.let { processLifecycle.removeObserver(it) }
            foregroundObserver = newObserver
            processLifecycle.addObserver(newObserver)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attach.run()
        } else {
            Handler(Looper.getMainLooper()).post(attach)
        }
    }

    /**
     * Drain the pending push-delivery store through the analytics pipeline,
     * cancelling each entry's WorkManager unique work so the two channels can't
     * both deliver. The exactly-once drain logic (cancel → claim → publish, with
     * per-entry isolation) lives in the shared [PendingDeliveryFlusher]; here we
     * only supply the analytics-pipeline transport and the push-specific logs.
     */
    @androidx.annotation.VisibleForTesting
    internal fun handoffPendingPushDeliveryToAnalyticsPipeline() {
        pushDeliveryFlusher.flush(
            callbacks = object : PendingDeliveryFlusher.Callbacks<PendingPushDeliveryMetric>() {
                override fun onSnapshot(count: Int) = pushLogger.logForegroundSnapshot(count)
                override fun onWorkCancelled(entry: PendingPushDeliveryMetric) =
                    pushLogger.logHandoffCancelledWorkManager(entry.deliveryId)
                override fun onPublished(entry: PendingPushDeliveryMetric) =
                    pushLogger.logHandoffPublishedToEventBus(entry.deliveryId)
                override fun onEntryFailed(entry: PendingPushDeliveryMetric, cause: Throwable) =
                    pushLogger.logHandoffEntryFailed(entry.deliveryId, cause)
                override fun onComplete(count: Int) = pushLogger.logHandoffComplete(count)
            }
        ) { entry ->
            eventBus.publish(
                Event.TrackPushMetricEvent(
                    event = Metric.Delivered,
                    deliveryId = entry.deliveryId,
                    deviceToken = entry.token
                )
            )
        }
    }

    /**
     * FCM only provides a push device token once through the [CustomerIOFirebaseMessagingService] when there is a new token assigned to the device. After that, it's up to you to get the device token.
     *
     * This can cause edge cases where a customer might never get a device token assigned to a profile. https://github.com/customerio/customerio-android/issues/61
     *
     * To fix this, it's recommended that each time your app starts up, you get the current push token and register it to the SDK. We do it for you automatically here as long as you initialize the MessagingPush module with the SDK.
     */
    private fun getCurrentFcmToken() {
        fcmTokenProvider.getCurrentToken { token ->
            token?.let {
                eventBus.publish(Event.RegisterDeviceTokenEvent(token))
            }
        }
    }

    companion object {
        internal const val MODULE_NAME = "MessagingPushFCM"

        // Held statically because ProcessLifecycleOwner is process-scoped.
        // Mutated only from the main thread inside `observeProcessForeground`
        // — the attach Runnable always posts to the main looper if needed —
        // so no additional synchronization is required.
        @Volatile
        @androidx.annotation.VisibleForTesting
        internal var foregroundObserver: LifecycleEventObserver? = null
    }
}
