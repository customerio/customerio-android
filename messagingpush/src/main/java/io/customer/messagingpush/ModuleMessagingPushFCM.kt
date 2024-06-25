package io.customer.messagingpush

import io.customer.messagingpush.di.appLifecycleCallbacks
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.lifecycle.MessagingPushLifecycleCallback
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.core.module.CustomerIOModule

class ModuleMessagingPushFCM @JvmOverloads constructor(
    override val moduleConfig: MessagingPushModuleConfig = MessagingPushModuleConfig.default()
) : CustomerIOModule<MessagingPushModuleConfig> {

    private val diGraph: SDKComponent
        get() = SDKComponent

    private val fcmTokenProvider
        get() = diGraph.androidSDKComponent?.fcmTokenProvider

    override val moduleName: String
        get() = MODULE_NAME

    override fun initialize() {
        getCurrentFcmToken()
        diGraph.appLifecycleCallbacks.registerCallback(
            MessagingPushLifecycleCallback(
                moduleConfig = moduleConfig,
                pushTrackingUtil = diGraph.pushTrackingUtil
            )
        )
    }

    /**
     * FCM only provides a push device token once through the [CustomerIOFirebaseMessagingService] when there is a new token assigned to the device. After that, it's up to you to get the device token.
     *
     * This can cause edge cases where a customer might never get a device token assigned to a profile. https://github.com/customerio/customerio-android/issues/61
     *
     * To fix this, it's recommended that each time your app starts up, you get the current push token and register it to the SDK. We do it for you automatically here as long as you initialize the MessagingPush module with the SDK.
     */
    private fun getCurrentFcmToken() {
        fcmTokenProvider?.getCurrentToken { token ->
            token?.let {
                eventBus.publish(Event.RegisterDeviceTokenEvent(token))
            }
        }
    }

    companion object {
        internal const val MODULE_NAME = "MessagingPushFCM"
    }
}
