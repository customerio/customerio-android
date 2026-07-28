package io.customer.messagingpush

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.pushDeliveryFlusher
import io.customer.messagingpush.di.pushLogger
import io.customer.messagingpush.di.pushTrackingUtil
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
        getCurrentFcmToken()
        subscribeToLifecycleEvents()
        observeProcessForeground()
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
