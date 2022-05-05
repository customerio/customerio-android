package io.customer.sdk.repository

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.queue.Queue
import io.customer.sdk.util.Logger

interface ProfileRepository {
    fun identify(identifier: String, attributes: CustomAttributes)
    fun clearIdentify()
}

class ProfileRepositoryImpl(
    private val deviceRepository: DeviceRepository,
    private val preferenceRepository: PreferenceRepository,
    private val backgroundQueue: Queue,
    private val logger: Logger
) : ProfileRepository {

    override fun identify(identifier: String, attributes: CustomAttributes) {
        logger.info("identify profile $identifier")
        logger.debug("identify profile $identifier, $attributes")

        val currentlyIdentifiedProfileIdentifier = preferenceRepository.getIdentifier()
        // The SDK calls identify() with the already identified profile for changing profile attributes.
        val isChangingIdentifiedProfile = currentlyIdentifiedProfileIdentifier != null && currentlyIdentifiedProfileIdentifier != identifier
        val isFirstTimeIdentifying = currentlyIdentifiedProfileIdentifier == null

        currentlyIdentifiedProfileIdentifier?.let { currentlyIdentifiedProfileIdentifier ->
            if (isChangingIdentifiedProfile) {
                logger.info("changing profile from id $currentlyIdentifiedProfileIdentifier to $identifier")

                logger.debug("deleting device token before identifying new profile")
                deviceRepository.deleteDeviceToken()
            }
        }

        val queueStatus = backgroundQueue.queueIdentifyProfile(identifier, currentlyIdentifiedProfileIdentifier, attributes)

        // don't modify the state of the SDK until we confirm we added a queue task successfully.
        if (!queueStatus.success) {
            logger.debug("failed to add identify task to queue")
            return
        }

        logger.debug("storing identifier on device storage $identifier")
        preferenceRepository.saveIdentifier(identifier)

        if (isFirstTimeIdentifying || isChangingIdentifiedProfile) {
            logger.debug("first time identified or changing identified profile")

            preferenceRepository.getDeviceToken()?.let {
                logger.debug("automatically registering device token to newly identified profile")
                deviceRepository.registerDeviceToken(it, emptyMap()) // no new attributes but default ones to pass so pass empty.
            }
        }
    }

    override fun clearIdentify() {
        preferenceRepository.getIdentifier()?.let { identifier ->
            preferenceRepository.removeIdentifier(identifier)
        }
    }
}
