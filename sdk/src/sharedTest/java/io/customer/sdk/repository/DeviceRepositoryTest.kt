package io.customer.sdk.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.android.core.util.Logger
import io.customer.commontest.BaseTest
import io.customer.sdk.data.request.Device
import io.customer.sdk.extensions.random
import io.customer.sdk.queue.Queue
import io.customer.sdk.repository.preference.SitePreferenceRepository
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@RunWith(AndroidJUnit4::class)
class DeviceRepositoryTest : BaseTest() {

    private val prefRepository: SitePreferenceRepository
        get() = di.sitePreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val loggerMock: Logger = mock()

    private lateinit var repository: DeviceRepository

    @Before
    override fun setup() {
        super.setup()

        repository = DeviceRepositoryImpl(
            config = cioConfig,
            deviceStore = deviceStore,
            sitePreferenceRepository = prefRepository,
            backgroundQueue = backgroundQueueMock,
            dateUtil = dateUtilStub,
            logger = loggerMock
        )
    }

    // registerDeviceToken

    @Test
    fun registerDeviceToken_givenNoIdentifiedProfile_expectDoNotAddTaskToBackgroundQueue_expectSaveToken() {
        val givenDeviceToken = String.random

        repository.registerDeviceToken(givenDeviceToken, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
        prefRepository.getDeviceToken() shouldBeEqualTo givenDeviceToken
    }

    @Test
    fun registerDeviceToken_givenEmptyDeviceToken_expectDoNotAddTaskToBackgroundQueue() {
        val givenDeviceToken = ""

        repository.registerDeviceToken(givenDeviceToken, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun registerDeviceToken_givenEmptyIdentifier_expectDoNotAddTaskToBackgroundQueue() {
        val givenDeviceToken = String.random
        prefRepository.saveIdentifier("")

        repository.registerDeviceToken(givenDeviceToken, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun registerDeviceToken_givenIdentifiedProfile_expectAddTaskToQueue_expectSaveToken() {
        val givenIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)

        repository.registerDeviceToken(givenDeviceToken, givenAttributes)

        verify(backgroundQueueMock).queueRegisterDevice(
            givenIdentifier,
            Device(
                token = givenDeviceToken,
                lastUsed = dateUtilStub.givenDate,
                attributes = deviceStore.buildDeviceAttributes() + givenAttributes
            )
        )
        prefRepository.getDeviceToken() shouldBeEqualTo givenDeviceToken
    }

    // deleteDeviceToken

    @Test
    fun deleteDeviceToken_givenNoDeviceToken_expectDoNotAddTaskToBackgroundQueue() {
        prefRepository.saveIdentifier(String.random)

        repository.deleteDeviceToken()

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun deleteDeviceToken_givenNoProfileIdentified_expectDoNotAddTaskToBackgroundQueue() {
        prefRepository.saveDeviceToken(String.random)

        repository.deleteDeviceToken()

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun deleteDeviceToken_givenDeviceTokenAndIdentifiedProfile_expectAddTaskToBackgroundQueue() {
        val givenDeviceToken = String.random
        val givenIdentifier = String.random
        prefRepository.saveDeviceToken(givenDeviceToken)
        prefRepository.saveIdentifier(givenIdentifier)

        repository.deleteDeviceToken()

        verify(backgroundQueueMock).queueDeletePushToken(givenIdentifier, givenDeviceToken)
    }

    // addCustomDeviceAttributes

    @Test
    fun addCustomDeviceAttributes_givenNoPushToken_expectDoNotRegisterPushToken() {
        val givenAttributes = mapOf(String.random to String.random)

        repository.addCustomDeviceAttributes(givenAttributes)

        // no token registered
        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun addCustomDeviceAttributes_givenExistingPushToken_expectRegisterPushTokenAndAttributes() {
        val givenAttributes = mapOf(String.random to String.random)
        val givenDeviceToken = String.random
        val givenIdentifier = String.random
        prefRepository.saveDeviceToken(givenDeviceToken)
        prefRepository.saveIdentifier(givenIdentifier)

        repository.addCustomDeviceAttributes(givenAttributes)

        verify(backgroundQueueMock).queueRegisterDevice(
            givenIdentifier,
            Device(
                token = givenDeviceToken,
                lastUsed = dateUtilStub.givenDate,
                attributes = deviceStore.buildDeviceAttributes() + givenAttributes
            )
        )
    }
}
