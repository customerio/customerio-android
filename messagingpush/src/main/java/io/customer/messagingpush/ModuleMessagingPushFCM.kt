package io.customer.messagingpush

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import androidx.work.await
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.pendingPushDeliveryStore
import io.customer.messagingpush.di.pushLogger
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.logger.PushNotificationLogger
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.store.PendingPushDeliveryMetric
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.core.di.workManagerProvider
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.data.store.PendingDeliveryStore
import io.customer.sdk.events.Metric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class ModuleMessagingPushFCM @JvmOverloads constructor(
    override val moduleConfig: MessagingPushModuleConfig = MessagingPushModuleConfig.default()
) : CustomerIOModule<MessagingPushModuleConfig> {

    private val fcmTokenProvider: DeviceTokenProvider
        get() = SDKComponent.android().fcmTokenProvider
    private val pushTrackingUtil = SDKComponent.pushTrackingUtil
    private val activityLifecycleCallbacks = SDKComponent.activityLifecycleCallbacks
    private val pendingPushDeliveryStore: PendingDeliveryStore<PendingPushDeliveryMetric>
        get() = SDKComponent.pendingPushDeliveryStore
    private val dispatchers: DispatchersProvider
        get() = SDKComponent.dispatchersProvider
    private val pushLogger: PushNotificationLogger
        get() = SDKComponent.pushLogger
    private val workManagerForCancel: WorkManager?
        get() = SDKComponent.workManagerProvider.getWorkManager()

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
     * Snapshot the pending store, cancel each entry's WorkManager unique work
     * so the worker won't also deliver, then publish each entry through the
     * analytics pipeline. Removes only the snapshotted keys at the end so
     * entries appended mid-handoff survive.
     *
     * Cancel happens before publish on purpose: a worker that is `ENQUEUED`
     * or running flips to `CANCELLED` immediately and won't issue its HTTP
     * call. Reversing the order would widen the window in which both
     * channels can race to deliver the same metric.
     */
    @androidx.annotation.VisibleForTesting
    internal fun handoffPendingPushDeliveryToAnalyticsPipeline() {
        CoroutineScope(dispatchers.background).launch {
            runCatching {
                val pending = pendingPushDeliveryStore.loadAll()
                if (pending.isEmpty()) {
                    pushLogger.logForegroundSnapshot(count = 0)
                    return@runCatching
                }
                pushLogger.logForegroundSnapshot(count = pending.size)

                val wm = workManagerForCancel
                pending.forEach { entry ->
                    // Only log "cancelled WM" when there's actually a WorkManager to
                    // cancel against; the async-tracker schedule path leaves no work
                    // for cancelUniqueWork to act on.
                    if (wm != null) {
                        wm.cancelUniqueWork(entry.deliveryId).await()
                        pushLogger.logHandoffCancelledWorkManager(entry.deliveryId)
                    }
                    eventBus.publish(
                        Event.TrackPushMetricEvent(
                            event = Metric.Delivered,
                            deliveryId = entry.deliveryId,
                            deviceToken = entry.token
                        )
                    )
                    pushLogger.logHandoffPublishedToEventBus(entry.deliveryId)
                }
                val flushedIds = pending.map { it.deliveryId }
                pendingPushDeliveryStore.removeAll(flushedIds)
                pushLogger.logHandoffComplete(count = flushedIds.size)
            }
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
