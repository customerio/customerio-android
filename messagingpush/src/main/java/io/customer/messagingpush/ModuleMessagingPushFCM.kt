package io.customer.messagingpush

import io.customer.messagingpush.di.deepLinkUtil
import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.di.pushTrackingUtil
import io.customer.messagingpush.lifecycle.MessagingPushLifecycleCallback
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.module.CustomerIOModule

class ModuleMessagingPushFCM internal constructor(
    override val moduleConfig: MessagingPushModuleConfig = MessagingPushModuleConfig(),
    private val overrideCustomerIO: CustomerIOInstance?,
    private val overrideDiGraph: CustomerIOComponent?
) : CustomerIOModule<MessagingPushModuleConfig> {

    @JvmOverloads
    constructor(config: MessagingPushModuleConfig = MessagingPushModuleConfig()) : this(
        moduleConfig = config,
        overrideCustomerIO = null,
        overrideDiGraph = null
    )

    private val customerIO: CustomerIOInstance
        get() = overrideCustomerIO ?: CustomerIO.instance()
    private val diGraph: CustomerIOComponent
        get() = overrideDiGraph ?: CustomerIO.instance().diGraph
    private val fcmTokenProvider by lazy { diGraph.fcmTokenProvider }

    override val moduleName: String
        get() = MODULE_NAME

    override fun initialize() {
        getCurrentFcmToken()
        diGraph.activityLifecycleCallbacks.registerCallback(
            MessagingPushLifecycleCallback(diGraph.deepLinkUtil, diGraph.pushTrackingUtil)
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
        fcmTokenProvider.getCurrentToken { token ->
            token?.let { customerIO.registerDeviceToken(token) }
        }
    }

    companion object {
        internal const val MODULE_NAME = "MessagingPushFCM"
    }
}
