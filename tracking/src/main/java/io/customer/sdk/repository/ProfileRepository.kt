package io.customer.sdk.repository

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.hooks.ModuleHook
import io.customer.sdk.queue.Queue
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.util.Logger

interface ProfileRepository {
    fun identify(identifier: String, attributes: CustomAttributes)
    fun clearIdentify()
    fun addCustomProfileAttributes(attributes: CustomAttributes)
}

internal class ProfileRepositoryImpl(
    private val deviceRepository: DeviceRepository,
    private val sitePreferenceRepository: SitePreferenceRepository,
    private val backgroundQueue: Queue,
    private val logger: Logger,
    private val hooksManager: HooksManager
) : ProfileRepository {

    override fun identify(identifier: String, attributes: CustomAttributes) {
        logger.info("identify profile $identifier")
        logger.debug("identify profile $identifier, $attributes")

        if (identifier.isBlank()) {
            logger.debug("Profile cannot be identified: Identifier is blank. Please retry with a valid, non-empty identifier.")
            return
        }

        val currentlyIdentifiedProfileIdentifier = sitePreferenceRepository.getIdentifier()
        // The SDK calls identify() with the already identified profile for changing profile attributes.
        val isChangingIdentifiedProfile =
            currentlyIdentifiedProfileIdentifier != null && currentlyIdentifiedProfileIdentifier != identifier
        val isFirstTimeIdentifying = currentlyIdentifiedProfileIdentifier == null

        currentlyIdentifiedProfileIdentifier?.let { currentlyIdentifiedProfileIdentifier ->
            if (isChangingIdentifiedProfile) {
                logger.info("changing profile from id $currentlyIdentifiedProfileIdentifier to $identifier")

                logger.debug("deleting device token before identifying new profile")
                deviceRepository.deleteDeviceToken()
            }
        }

        val queueStatus = backgroundQueue.queueIdentifyProfile(
            identifier,
            currentlyIdentifiedProfileIdentifier,
            attributes
        )

        // Don't modify the state of the SDK's data until we confirm we added a queue task successfully. This could put the Customer.io API
        // out-of-sync with the SDK's state and cause many future HTTP errors.
        // Therefore, if adding the task to the queue failed, ignore the request and fail early.
        if (!queueStatus.success) {
            logger.debug("failed to add identify task to queue")
            return
        }

        logger.debug("storing identifier on device storage $identifier")
        sitePreferenceRepository.saveIdentifier(identifier)

        hooksManager.onHookUpdate(
            hook = ModuleHook.ProfileIdentifiedHook(identifier)
        )

        if (isFirstTimeIdentifying || isChangingIdentifiedProfile) {
            logger.debug("first time identified or changing identified profile")

            sitePreferenceRepository.getDeviceToken()?.let {
                logger.debug("automatically registering device token to newly identified profile")
                deviceRepository.registerDeviceToken(
                    it,
                    emptyMap()
                ) // no new attributes but default ones to pass so pass empty.
            }
        }
    }

    override fun addCustomProfileAttributes(attributes: CustomAttributes) {
        logger.debug("adding profile attributes request made")

        val currentlyIdentifiedProfileId = sitePreferenceRepository.getIdentifier()

        if (currentlyIdentifiedProfileId == null) {
            logger.debug("no profile is currently identified. ignoring request to add attributes to a profile")
            return
        }

        identify(currentlyIdentifiedProfileId, attributes)
    }

    override fun clearIdentify() {
        logger.debug("clearing identified profile request made")

        val currentlyIdentifiedProfileId = sitePreferenceRepository.getIdentifier()

        if (currentlyIdentifiedProfileId == null) {
            logger.info("no profile is currently identified. ignoring request to clear identified profile")
            return
        }

        // notify hooks about identifier being cleared
        hooksManager.onHookUpdate(
            ModuleHook.BeforeProfileStoppedBeingIdentified(
                currentlyIdentifiedProfileId
            )
        )

        // delete token from profile to prevent sending the profile pushes when they are not identified in the SDK.
        deviceRepository.deleteDeviceToken()

        // delete identified from device storage to not associate future SDK calls to this profile
        logger.debug("clearing profile from device storage")
        sitePreferenceRepository.removeIdentifier(currentlyIdentifiedProfileId)
    }
}
