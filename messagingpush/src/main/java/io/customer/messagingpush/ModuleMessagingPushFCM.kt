package io.customer.messagingpush

import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.CustomerIOModule
import io.customer.sdk.di.CustomerIOComponent

class ModuleMessagingPushFCM internal constructor(
    private val overrideCustomerIO: CustomerIOInstance?,
    private val overrideDiGraph: CustomerIOComponent?
) : CustomerIOModule {

    constructor() : this(overrideCustomerIO = null, overrideDiGraph = null)

    private val customerIO: CustomerIOInstance
        get() = overrideCustomerIO ?: CustomerIO.instance()
    private val diGraph: CustomerIOComponent
        get() = overrideDiGraph ?: CustomerIO.instance().diGraph

    private val fcmTokenProvider by lazy { diGraph.fcmTokenProvider }

    override val moduleName: String
        get() = "MessagingPushFCM"

    override fun initialize() {
        getCurrentFcmToken()
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
            token?.let { token -> customerIO.registerDeviceToken(token) }
        }
    }
}
