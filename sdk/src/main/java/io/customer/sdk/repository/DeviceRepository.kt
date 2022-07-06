package io.customer.sdk.repository

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.queue.Queue
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger

interface DeviceRepository {
    fun registerDeviceToken(deviceToken: String, attributes: CustomAttributes)
    fun deleteDeviceToken()
    fun addCustomDeviceAttributes(attributes: CustomAttributes)
}

internal class DeviceRepositoryImpl(
    private val config: CustomerIOConfig,
    private val deviceStore: DeviceStore,
    private val preferenceRepository: PreferenceRepository,
    private val backgroundQueue: Queue,
    private val dateUtil: DateUtil,
    private val logger: Logger
) : DeviceRepository {

    override fun registerDeviceToken(deviceToken: String, attributes: CustomAttributes) {
        val attributes = createDeviceAttributes(attributes)

        logger.info("registering device token $deviceToken, attributes: $attributes")

        // persist the device token for use later on such as automatically registering device token with a profile
        // that gets identified later on.
        logger.debug("storing device token to device storage $deviceToken")
        preferenceRepository.saveDeviceToken(deviceToken)

        val identifiedProfileId = preferenceRepository.getIdentifier()
        if (identifiedProfileId == null) {
            logger.info("no profile identified, so not registering device token to a profile")
            return
        }

        val device = Device(
            token = deviceToken,
            lastUsed = dateUtil.now,
            attributes = attributes
        )

        // if task doesn't successfully get added to the queue, it does not break the SDK's state. So, we can ignore the result of adding task to queue.
        backgroundQueue.queueRegisterDevice(identifiedProfileId, device)
    }

    override fun addCustomDeviceAttributes(attributes: CustomAttributes) {
        logger.debug("adding custom device attributes request made")

        val existingDeviceToken = preferenceRepository.getDeviceToken()

        if (existingDeviceToken == null) {
            logger.debug("no device token yet registered. ignoring request to add custom device attributes")
            return
        }

        registerDeviceToken(existingDeviceToken, attributes)
    }

    private fun createDeviceAttributes(customAddedAttributes: CustomAttributes): Map<String, Any> {
        if (!config.autoTrackDeviceAttributes) return customAddedAttributes

        val defaultAttributes = deviceStore.buildDeviceAttributes()

        return defaultAttributes + customAddedAttributes // order matters! allow customer to override default values if they wish.
    }

    override fun deleteDeviceToken() {
        logger.info("deleting device token request made")

        val existingDeviceToken = preferenceRepository.getDeviceToken()
        if (existingDeviceToken == null) {
            logger.info("no device token exists so ignoring request to delete")
            return
        }

        // Do not delete push token from device storage. The token is valid
        // once given to SDK. We need it for future profile identifications.

        val identifiedProfileId = preferenceRepository.getIdentifier()
        if (identifiedProfileId == null) {
            logger.info("no profile identified so not removing device token from profile")
            return
        }

        // if task doesn't successfully get added to the queue, it does not break the SDK's state. So, we can ignore the result of adding task to queue.
        backgroundQueue.queueDeletePushToken(identifiedProfileId, existingDeviceToken)
    }
}
