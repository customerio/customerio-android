package io.customer.sdk.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.util.Logger
import io.customer.sdk.utils.random
import org.amshove.kluent.shouldBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest : BaseTest() {

    private val prefRepository: PreferenceRepository
        get() = di.sharedPreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val loggerMock: Logger = mock()
    private val deviceRepositoryMock: DeviceRepository = mock()

    private lateinit var repository: ProfileRepository

    @Before
    override fun setup() {
        super.setup()

        repository = ProfileRepositoryImpl(
            deviceRepository = deviceRepositoryMock,
            preferenceRepository = prefRepository,
            backgroundQueue = backgroundQueueMock,
            logger = loggerMock
        )
    }

    // identify

    @Test
    fun identify_givenFirstTimeIdentify_givenNoDeviceTokenRegistered_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val newIdentifier = String.random
        val givenAttributes = mapOf("name" to String.random)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).queueIdentifyProfile(
            newIdentifier = newIdentifier,
            oldIdentifier = null,
            attributes = givenAttributes
        )
        verifyNoMoreInteractions(backgroundQueueMock)
    }

    @Test
    fun identify_givenFirstTimeIdentify_givenDeviceTokenExists_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectRegisterDeviceToken() {
        val newIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        inOrder(backgroundQueueMock, deviceRepositoryMock).apply {
            verify(backgroundQueueMock).queueIdentifyProfile(
                newIdentifier = newIdentifier,
                oldIdentifier = null,
                attributes = givenAttributes
            )
            // Register needs to happen after identify added to queue as it has a blocking group set to new profile identified
            verify(deviceRepositoryMock).registerDeviceToken(givenDeviceToken, emptyMap())
        }
        verifyNoMoreInteractions(backgroundQueueMock)
    }

    @Test
    fun identify_givenNotFirstTimeIdentify_givenNoDeviceTokenRegistered_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val givenIdentifier = String.random
        val newIdentifier = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).queueIdentifyProfile(
            newIdentifier = newIdentifier,
            oldIdentifier = givenIdentifier,
            attributes = givenAttributes
        )
        verifyNoMoreInteractions(backgroundQueueMock)
    }

    @Test
    fun identify_givenNotFirstTimeIdentify_givenDeviceTokenExists_expectIdentifyBackgroundQueue_expectDeleteToken_expectRegisterDeviceToken() {
        val givenIdentifier = String.random
        val newIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        inOrder(backgroundQueueMock, deviceRepositoryMock).apply {
            // order of adding tasks to queue matter to prevent locking running background queue tasks. Some tasks may belong to a group and that group needs to exist in the queue!

            verify(deviceRepositoryMock).deleteDeviceToken()

            verify(backgroundQueueMock).queueIdentifyProfile(
                newIdentifier = newIdentifier,
                oldIdentifier = givenIdentifier,
                attributes = givenAttributes
            )

            verify(deviceRepositoryMock).registerDeviceToken(givenDeviceToken, emptyMap())
        }
        verifyNoMoreInteractions(backgroundQueueMock)
    }

    @Test
    fun identify_givenIdentifyAlreadyIdentifiedProfile_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val givenIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(givenIdentifier, givenAttributes)

        verify(backgroundQueueMock).queueIdentifyProfile(
            newIdentifier = givenIdentifier,
            oldIdentifier = givenIdentifier,
            attributes = givenAttributes
        )
        verifyNoMoreInteractions(backgroundQueueMock)
    }

    // clearIdentify

    @Test
    fun clearIdentify_verifyWhenCustomerIdentifyIsClearedItsRemovedInPrefsRepo() {
        val givenIdentifier = String.random
        prefRepository.saveIdentifier(givenIdentifier)

        repository.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()
    }

    @Test
    fun clearIdentify_givenNoPreviouslyIdentifiedProfile_expectIgnoreRequest() {
        repository.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()
    }

    // addCustomProfileAttributes

    @Test
    fun addCustomProfileAttributes_givenProfileIdentified_expectDoNotIdentifyProfile() {
        val givenAttributes = mapOf(String.random to String.random)

        repository.addCustomProfileAttributes(givenAttributes)

        // do not identify profile
        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun addCustomProfileAttributes_givenExistingProfileIdentified_expectAddAttributesToProfile() {
        val givenAttributes = mapOf(String.random to String.random)
        val givenIdentifier = String.random
        prefRepository.saveIdentifier(givenIdentifier)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.addCustomProfileAttributes(givenAttributes)

        // assert that attributes have been added to a profile
        verify(backgroundQueueMock).queueIdentifyProfile(givenIdentifier, givenIdentifier, givenAttributes)
    }
}
