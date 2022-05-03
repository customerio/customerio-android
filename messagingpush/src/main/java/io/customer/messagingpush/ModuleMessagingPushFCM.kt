package io.customer.messagingpush

import io.customer.messagingpush.di.fcmTokenProvider
import io.customer.messagingpush.provider.FCMTokenProvider
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.CustomerIOModule
import io.customer.sdk.di.CustomerIOComponent

class ModuleMessagingPushFCM : CustomerIOModule {

    internal lateinit var customerIO: CustomerIOInstance
    internal lateinit var diGraph: CustomerIOComponent

    private val fcmTokenProvider: FCMTokenProvider
        get() = diGraph.fcmTokenProvider

    override val moduleName: String
        get() = "MessagingPushFCM"

    override fun initialize(customerIO: CustomerIOInstance, dependencies: CustomerIOComponent) {
        this.diGraph = dependencies
        this.customerIO = customerIO

        getCurrentFcmToken()
    }

    private fun getCurrentFcmToken() {
        fcmTokenProvider.getCurrentToken { token ->
            token?.let { token -> customerIO.registerDeviceToken(token) }
        }
    }
}
