package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Device
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger
import io.customer.sdk.utils.random
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOClientTest : BaseTest() {

    private val prefRepository: PreferenceRepository
        get() = di.sharedPreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val loggerMock: Logger = mock()

    private lateinit var customerIOClient: CustomerIOClient

    @Before
    override fun setup() {
        super.setup()

        customerIOClient = CustomerIOClient(
            config = cioConfig,
            deviceStore = deviceStore,
            preferenceRepository = prefRepository,
            backgroundQueue = backgroundQueueMock,
            dateUtil = dateUtilStub,
            logger = loggerMock
        )
    }

    // identify

    @Test
    fun identify_givenFirstTimeIdentify_givenNoDeviceTokenRegistered_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val newIdentifier = String.random
        val givenAttributes = mapOf("name" to String.random)
        whenever(backgroundQueueMock.queueIdentifyProfile(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.identify(newIdentifier, givenAttributes)

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

        customerIOClient.identify(newIdentifier, givenAttributes)

        inOrder(backgroundQueueMock).apply {
            verify(backgroundQueueMock).queueIdentifyProfile(
                newIdentifier = newIdentifier,
                oldIdentifier = null,
                attributes = givenAttributes
            )
            // Register needs to happen after identify added to queue as it has a blocking group set to new profile identified
            verify(backgroundQueueMock).queueRegisterDevice(
                newIdentifier,
                Device(
                    token = givenDeviceToken,
                    lastUsed = dateUtilStub.givenDate,
                    attributes = deviceStore.buildDeviceAttributes()
                )
            )
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

        customerIOClient.identify(newIdentifier, givenAttributes)

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

        customerIOClient.identify(newIdentifier, givenAttributes)

        inOrder(backgroundQueueMock).apply {
            // order of adding tasks to queue matter to prevent locking running background queue tasks. Some tasks may belong to a group and that group needs to exist in the queue!

            verify(backgroundQueueMock).queueDeletePushToken(
                givenIdentifier,
                givenDeviceToken
            )

            verify(backgroundQueueMock).queueIdentifyProfile(
                newIdentifier = newIdentifier,
                oldIdentifier = givenIdentifier,
                attributes = givenAttributes
            )

            verify(backgroundQueueMock).queueRegisterDevice(
                newIdentifier,
                Device(
                    token = givenDeviceToken,
                    lastUsed = dateUtilStub.givenDate,
                    attributes = deviceStore.buildDeviceAttributes()
                )
            )
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

        customerIOClient.identify(givenIdentifier, givenAttributes)

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

        customerIOClient.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()
    }

    @Test
    fun clearIdentify_givenNoPreviouslyIdentifiedProfile_expectIgnoreRequest() {
        customerIOClient.clearIdentify()

        prefRepository.getIdentifier().shouldBeNull()
    }

    // registerDeviceToken

    @Test
    fun registerDeviceToken_givenNoIdentifiedProfile_expectDoNotAddTaskToBackgroundQueue_expectSaveToken() {
        val givenDeviceToken = String.random

        customerIOClient.registerDeviceToken(givenDeviceToken, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
        prefRepository.getDeviceToken() shouldBeEqualTo givenDeviceToken
    }

    @Test
    fun registerDeviceToken_givenIdentifiedProfile_expectAddTaskToQueue_expectSaveToken() {
        val givenIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)

        customerIOClient.registerDeviceToken(givenDeviceToken, givenAttributes)

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

    // addCustomDeviceAttributes

    @Test
    fun addCustomDeviceAttributes_givenNoPushToken_expectDoNotRegisterPushToken() {
        val givenAttributes = mapOf(String.random to String.random)

        customerIOClient.addCustomDeviceAttributes(givenAttributes)

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

        customerIOClient.addCustomDeviceAttributes(givenAttributes)

        // a token got registered
        verify(backgroundQueueMock).addTask(
            QueueTaskType.RegisterDeviceToken,
            RegisterPushNotificationQueueTaskData(
                givenIdentifier,
                Device(
                    token = givenDeviceToken,
                    lastUsed = dateUtilStub.givenDate,
                    attributes = deviceStore.buildDeviceAttributes() + givenAttributes
                )
            ),
            groupStart = QueueTaskGroup.RegisterPushToken(givenDeviceToken),
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(givenIdentifier))
        )
    }

    // addCustomDeviceAttributes

    @Test
    fun addCustomDeviceAttributes_givenNoPushToken_expectDoNotRegisterPushToken() {
        val givenAttributes = mapOf(String.random to String.random)

        customerIOClient.addCustomDeviceAttributes(givenAttributes)

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

        customerIOClient.addCustomDeviceAttributes(givenAttributes)

        // a token got registered
        verify(backgroundQueueMock).addTask(
            QueueTaskType.RegisterDeviceToken,
            RegisterPushNotificationQueueTaskData(
                givenIdentifier,
                Device(
                    token = givenDeviceToken,
                    lastUsed = dateUtilStub.givenDate,
                    attributes = deviceStore.buildDeviceAttributes() + givenAttributes
                )
            ),
            groupStart = QueueTaskGroup.RegisterPushToken(givenDeviceToken),
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(givenIdentifier))
        )
    }

    // addCustomProfileAttributes

    @Test
    fun addCustomProfileAttributes_givenProfileIdentified_expectDoNotIdentifyProfile() {
        val givenAttributes = mapOf(String.random to String.random)

        customerIOClient.addCustomProfileAttributes(givenAttributes)

        // do not identify profile
        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun addCustomProfileAttributes_givenExistingProfileIdentified_expectAddAttributesToProfile() {
        val givenAttributes = mapOf(String.random to String.random)
        val givenIdentifier = String.random
        prefRepository.saveIdentifier(givenIdentifier)
        whenever(backgroundQueueMock.addTask(any(), any(), anyOrNull(), anyOrNull())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.addCustomProfileAttributes(givenAttributes)

        // assert that attributes have been added to a profile
        verify(backgroundQueueMock).addTask(
            QueueTaskType.IdentifyProfile,
            IdentifyProfileQueueTaskData(givenIdentifier, givenAttributes),
            groupStart = null,
            blockingGroups = listOf(QueueTaskGroup.IdentifyProfile(givenIdentifier))
        )
    }

    // deleteDeviceToken

    @Test
    fun deleteDeviceToken_givenNoDeviceToken_expectDoNotAddTaskToBackgroundQueue() {
        prefRepository.saveIdentifier(String.random)

        customerIOClient.deleteDeviceToken()

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun deleteDeviceToken_givenNoProfileIdentified_expectDoNotAddTaskToBackgroundQueue() {
        prefRepository.saveDeviceToken(String.random)

        customerIOClient.deleteDeviceToken()

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun deleteDeviceToken_givenDeviceTokenAndIdentifiedProfile_expectAddTaskToBackgroundQueue() {
        val givenDeviceToken = String.random
        val givenIdentifier = String.random
        prefRepository.saveDeviceToken(givenDeviceToken)
        prefRepository.saveIdentifier(givenIdentifier)

        customerIOClient.deleteDeviceToken()

        verify(backgroundQueueMock).queueDeletePushToken(givenIdentifier, givenDeviceToken)
    }

    // track

    @Test
    fun track_givenNoProfileIdentified_expectDoNotAddTaskBackgroundQueue() {
        customerIOClient.track(EventType.event, String.random, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun track_givenProfileIdentified_expectAddTaskBackgroundQueue() {
        val givenIdentifier = String.random
        val givenTrackEventName = String.random
        val givenAttributes = mapOf("foo" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)

        customerIOClient.track(EventType.event, givenTrackEventName, givenAttributes)

        verify(backgroundQueueMock).queueTrack(
            givenIdentifier,
            givenTrackEventName,
            EventType.event,
            givenAttributes
        )
    }

    @Test
    fun trackMetric_expectAddEventToBackgroundQueue() {
        val givenDeliveryId = String.random
        val givenEvent = MetricEvent.opened
        val givenDeviceToken = String.random

        customerIOClient.trackMetric(givenDeliveryId, givenEvent, givenDeviceToken)

        verify(backgroundQueueMock).queueTrackMetric(
            givenDeliveryId,
            givenDeviceToken,
            givenEvent
        )
    }
}
