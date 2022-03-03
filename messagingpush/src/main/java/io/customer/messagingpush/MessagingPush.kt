package io.customer.messagingpush

import io.customer.messagingpush.api.MessagingPushApi
import io.customer.messagingpush.data.request.MetricEvent
import io.customer.messagingpush.di.MessagingPushDiGraph
import io.customer.messagingpush.hooks.MessagingPushModuleHookProvider
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger

interface MessagingPushInstance {
    fun registerDeviceToken(deviceToken: String)
    fun deleteDeviceToken()
    fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String,
    )
}

// Convenient internal constructor used internally to get instance without needing to worry about providing all required constructors of CustomerIO class.
class MessagingPush internal constructor(
    private val siteId: String
) : MessagingPushInstance, ProfileIdentifiedHook {

    // Constructor for customers to use
    constructor(customerIO: CustomerIOInstance) : this(customerIO.siteId)

    companion object {
        @JvmStatic
        fun instance(): MessagingPush {
            val sharedCustomerIOInstance = CustomerIO.instance()

            return MessagingPush(sharedCustomerIOInstance)
        }
    }

    // Since this class is at the top-most level of the MessagingPush module,
    // we get instances from the DiGraph, not through constructor dependency injection.
    private val trackingModuleDiGraph: CustomerIOComponent
        get() = CustomerIOComponent.getInstance(siteId)

    private val diGraph: MessagingPushDiGraph
        get() = MessagingPushDiGraph.getInstance(siteId)

    private val logger: Logger
        get() = trackingModuleDiGraph.logger

    private val api: MessagingPushApi
        get() = diGraph.api

    private val preferenceRepository: PreferenceRepository
        get() = trackingModuleDiGraph.sharedPreferenceRepository

    init {
        logger.info("MessagingPush module setup with SDK")

        logger.debug("registering ModulePush module hooks with SDK")
        val hooks = trackingModuleDiGraph.hooks
        val messagingPushHooksProvider = MessagingPushModuleHookProvider(siteId)
        hooks.addProvider(HookModule.MESSAGING_PUSH, messagingPushHooksProvider)
    }

    /**
     * Register a new device token with Customer.io, associated with the current active customer. If there
     * is no active customer, this will fail to register the device
     */
    override fun registerDeviceToken(deviceToken: String) = api.registerDeviceToken(deviceToken)

    /**
     * Delete the currently registered device token
     */
    override fun deleteDeviceToken() = api.deleteDeviceToken()

    /**
     * Track a push metric
     */
    override fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String) = api.trackMetric(
        deliveryID = deliveryID,
        event = event,
        deviceToken = deviceToken
    )

    override fun beforeIdentifiedProfileChange(oldIdentifier: String, newIdentifier: String) {
        logger.debug("hook: deleting device token before identifying new profile")

        deleteDeviceToken()
    }

    override fun profileIdentified(identifier: String) {
        val existingDeviceToken = preferenceRepository.getDeviceToken()
        if (existingDeviceToken == null) {
            logger.debug("hook: no push token stored so not automatically registering token to profile")
            return
        }

        logger.debug("hook: automatically registering token to profile identified. token: $existingDeviceToken")

        registerDeviceToken(existingDeviceToken)
    }

    override fun profileStoppedBeingIdentified(oldIdentifier: String) {
        logger.debug("hook: deleting device token from profile no longer identified")

        deleteDeviceToken()
    }
}
