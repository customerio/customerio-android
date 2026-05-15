package io.customer.messagingpush

import androidx.lifecycle.Lifecycle
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.pendingPushDeliveryStore
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.messagingpush.store.PendingPushDeliveryStore
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.core.module.CustomerIOModule
import io.customer.sdk.core.util.DispatchersProvider
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
    private val pendingPushDeliveryStore: PendingPushDeliveryStore
        get() = SDKComponent.pendingPushDeliveryStore
    private val dispatchers: DispatchersProvider
        get() = SDKComponent.dispatchersProvider

    override val moduleName: String
        get() = MODULE_NAME

    override fun initialize() {
        getCurrentFcmToken()
        subscribeToLifecycleEvents()
        flushPendingPushDeliveryMetrics()
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
     * At app launch, drain any push-delivered metrics that were observed locally
     * but never confirmed by the primary delivery path (WorkManager / direct
     * HTTP). For each pending entry we publish a [Event.TrackPushMetricEvent] so
     * the analytics pipeline can deliver it, then remove that specific entry.
     *
     * Disk I/O (the JSON read and per-entry removes) MUST happen off the main
     * thread, so the sequence is dispatched on the background dispatcher.
     */
    private fun flushPendingPushDeliveryMetrics() {
        CoroutineScope(dispatchers.background).launch {
            runCatching {
                val pending = pendingPushDeliveryStore.loadAll()
                if (pending.isEmpty()) return@runCatching

                pending.forEach { entry ->
                    eventBus.publish(
                        Event.TrackPushMetricEvent(
                            event = Metric.Delivered,
                            deliveryId = entry.deliveryId,
                            deviceToken = entry.token
                        )
                    )
                    // Remove only the entries we actually flushed. Using
                    // removeAll() would race with append() and silently drop
                    // pushes that arrived mid-flush.
                    pendingPushDeliveryStore.remove(entry.deliveryId)
                }
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
    }
}
