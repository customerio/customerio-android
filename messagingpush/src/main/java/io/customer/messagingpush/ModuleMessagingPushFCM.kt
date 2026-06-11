package io.customer.messagingpush

import androidx.lifecycle.Lifecycle
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.liveNotificationManager
import io.customer.messagingpush.di.liveNotificationRegistrar
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.livenotification.LiveNotificationData
import io.customer.messagingpush.provider.DeviceTokenProvider
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.core.module.CustomerIOModule
import java.util.UUID
import kotlinx.coroutines.flow.filter

class ModuleMessagingPushFCM @JvmOverloads constructor(
    override val moduleConfig: MessagingPushModuleConfig = MessagingPushModuleConfig.default()
) : CustomerIOModule<MessagingPushModuleConfig> {

    private val fcmTokenProvider: DeviceTokenProvider
        get() = SDKComponent.android().fcmTokenProvider
    private val pushTrackingUtil = SDKComponent.pushTrackingUtil
    private val activityLifecycleCallbacks = SDKComponent.activityLifecycleCallbacks

    override val moduleName: String
        get() = MODULE_NAME

    override fun initialize() {
        // Live notifications are opt-in: only wire up registration when the host app
        // enabled at least one activity type. Start before requesting the token so the
        // registrar observes the resulting RegisterDeviceTokenEvent.
        if (moduleConfig.liveNotificationTypes.isNotEmpty()) {
            SDKComponent.liveNotificationRegistrar.start()
        }
        getCurrentFcmToken()
        subscribeToLifecycleEvents()
    }

    /**
     * Starts a live notification locally for a built-in template type. The SDK
     * generates a unique activity id, renders the notification immediately, and
     * registers the instance with Customer.io so the backend can push updates.
     *
     * @return the generated `activity_id`, used to correlate subsequent updates.
     */
    fun startLiveNotification(data: LiveNotificationData): String =
        startLiveNotification(data.activityType, data.fields())

    /**
     * Starts a live notification locally for a customer-defined [activityType]
     * (one registered via [MessagingPushModuleConfig.Builder.registerLiveNotificationTypes]).
     * Custom types have no built-in template, so a
     * [io.customer.messagingpush.data.communication.CustomerIOPushNotificationCallback.createLiveNotification]
     * must render them.
     *
     * @param data flattened fields delivered to the renderer.
     * @return the generated `activity_id`.
     */
    fun startLiveNotification(activityType: String, data: Map<String, Any?>): String {
        val activityId = UUID.randomUUID().toString()
        SDKComponent.liveNotificationManager.start(activityId, activityType, data)
        return activityId
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
