package io.customer.sdk.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.commontest.BaseTest
import io.customer.sdk.extensions.random
import io.customer.sdk.hooks.HooksManager
import io.customer.sdk.hooks.ModuleHook
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.repository.preference.SitePreferenceRepository
import io.customer.sdk.util.Logger
import org.amshove.kluent.internal.assertEquals
import org.amshove.kluent.shouldBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.*

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest : BaseTest() {

    private val prefRepository: SitePreferenceRepository
        get() = di.sitePreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val loggerMock: Logger = mock()
    private val hooksManager: HooksManager = mock()
    private val deviceRepositoryMock: DeviceRepository = mock()

    private lateinit var repository: ProfileRepository

    @Before
    override fun setup() {
        super.setup()

        repository = ProfileRepositoryImpl(
            deviceRepository = deviceRepositoryMock,
            sitePreferenceRepository = prefRepository,
            backgroundQueue = backgroundQueueMock,
            logger = loggerMock,
            hooksManager = hooksManager
        )
    }

    // identify

    @Test
    fun identify_givenFirstTimeIdentify_givenNoDeviceTokenRegistered_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val newIdentifier = String.random
        val givenAttributes = mapOf("name" to String.random)
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).queueIdentifyProfile(
            newIdentifier = newIdentifier,
            oldIdentifier = null,
            attributes = givenAttributes
        )
        verifyNoMoreInteractions(backgroundQueueMock)
    }

    @Test
    fun identify_givenEmptyIdentifier_expectNoIdentifyBackgroundQueue_expectIdentifierNotSaved() {
        val newIdentifier = ""
        val givenAttributes = mapOf("name" to String.random)
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        verifyNoInteractions(backgroundQueueMock)

        verify(backgroundQueueMock, times(0)).queueIdentifyProfile(
            newIdentifier = newIdentifier,
            oldIdentifier = null,
            attributes = givenAttributes
        )

        prefRepository.getIdentifier().shouldBeNull()
    }

    @Test
    fun identify_givenFirstTimeIdentify_givenDeviceTokenExists_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectProfileIdentifiedHookUpdateWithCorrectIdentifier_expectRegisterDeviceToken() {
        val newIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.identify(newIdentifier, givenAttributes)

        argumentCaptor<ModuleHook.ProfileIdentifiedHook>().apply {
            verify(hooksManager, times(1)).onHookUpdate(capture())
            assertEquals(newIdentifier, firstValue.identifier)
        }

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
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

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
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

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
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

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
    fun clearIdentify_verifyWhenCustomerIdentifyIsClearedItsRemovedInPrefsRepo_expectDeleteDeviceToken() {
        val givenIdentifier = String.random
        prefRepository.saveIdentifier(givenIdentifier)

        repository.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()
        verify(deviceRepositoryMock).deleteDeviceToken()
    }

    @Test
    fun clearIdentify_givenNoPreviouslyIdentifiedProfile_expectIgnoreRequest_expectDontDeleteDeviceToken_expectDontUpdateHook() {
        repository.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()

        verify(deviceRepositoryMock, never()).deleteDeviceToken()
        verify(hooksManager, never()).onHookUpdate(any())
    }

    @Test
    fun clearIdentify_givenPreviouslyIdentifiedProfile_expectHookUpdate() {
        val givenIdentifier = String.random
        prefRepository.saveIdentifier(givenIdentifier)

        repository.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()

        val argumentCaptor =
            argumentCaptor<ModuleHook.BeforeProfileStoppedBeingIdentified>()

        verify(hooksManager, times(1)).onHookUpdate(
            argumentCaptor.capture()
        )
        assertEquals(givenIdentifier, argumentCaptor.firstValue.identifier)
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
        whenever(
            backgroundQueueMock.queueIdentifyProfile(
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
            )
        ).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        repository.addCustomProfileAttributes(givenAttributes)

        // assert that attributes have been added to a profile
        verify(backgroundQueueMock).queueIdentifyProfile(
            givenIdentifier,
            givenIdentifier,
            givenAttributes
        )
    }
}
