package io.customer.messagingpush

import io.customer.base.comunication.Action
import io.customer.messagingpush.hooks.MessagingPushModuleHookProvider
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.di.CustomerIOComponent
import io.customer.sdk.hooks.HookModule
import io.customer.sdk.hooks.hooks.ProfileIdentifiedHook
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger

interface MessagingPushInstance {
    fun registerDeviceToken(deviceToken: String): Action<Unit>
    fun deleteDeviceToken(): Action<Unit>
}

class MessagingPush(private val customerIO: CustomerIOInstance) : MessagingPushInstance, ProfileIdentifiedHook {

    companion object {
        @JvmStatic
        fun instance(): MessagingPush {
            val sharedCustomerIOInstance = CustomerIO.instance()

            return MessagingPush(sharedCustomerIOInstance)
        }
    }

    private val siteId: String
        get() = customerIO.siteId

    // Since this class is at the top-most level of the MessagingPush module,
    // we get instances from the DiGraph, not through constructor dependency injection.
    val trackingModuleDiGraph: CustomerIOComponent
        get() = CustomerIOComponent.getInstance(siteId)

    private val logger: Logger
        get() = trackingModuleDiGraph.logger

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
    override fun registerDeviceToken(deviceToken: String): Action<Unit> = customerIO.registerDeviceToken(deviceToken)

    /**
     * Delete the currently registered device token
     */
    override fun deleteDeviceToken(): Action<Unit> = customerIO.deleteDeviceToken()

    override fun beforeIdentifiedProfileChange(oldIdentifier: String, newIdentifier: String) {
        logger.debug("hook: deleting device token before identifying new profile")

        deleteDeviceToken().enqueue()
    }

    override fun profileIdentified(identifier: String) {
        val existingDeviceToken = preferenceRepository.getDeviceToken()
        if (existingDeviceToken == null) {
            logger.debug("hook: no push token stored so not automatically registering token to profile")
            return
        }

        logger.debug("hook: automatically registering token to profile identified. token: $existingDeviceToken")

        registerDeviceToken(existingDeviceToken).enqueue()
    }

    override fun profileStoppedBeingIdentified(oldIdentifier: String) {
        logger.debug("hook: deleting device token from profile no longer identified")

        deleteDeviceToken().enqueue()
    }
}
