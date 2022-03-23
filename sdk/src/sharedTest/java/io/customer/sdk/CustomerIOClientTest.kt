package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.common_test.DateUtilStub
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Event
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.IdentifyProfileQueueTaskData
import io.customer.sdk.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueModifyResult
import io.customer.sdk.queue.type.QueueStatus
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger
import io.customer.sdk.utils.random
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOClientTest : BaseTest() {

    private val prefRepository: PreferenceRepository
        get() = di.sharedPreferenceRepository
    private val backgroundQueueMock: Queue = mock()
    private val dateUtilStub = DateUtilStub()
    private val loggerMock: Logger = mock()

    private lateinit var customerIOClient: CustomerIOClient

    @Before
    override fun setup() {
        super.setup()

        customerIOClient = CustomerIOClient(
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
        whenever(backgroundQueueMock.addTask(any(), any())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.IdentifyProfile, IdentifyProfileQueueTaskData(newIdentifier, givenAttributes))
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.DeletePushToken), any())
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.RegisterDeviceToken), any())
    }

    @Test
    fun identify_givenFirstTimeIdentify_givenDeviceTokenExists_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectRegisterDeviceToken() {
        val newIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(backgroundQueueMock.addTask(any(), any())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.IdentifyProfile, IdentifyProfileQueueTaskData(newIdentifier, givenAttributes))
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.DeletePushToken), any())
        verify(backgroundQueueMock).addTask(QueueTaskType.RegisterDeviceToken, RegisterPushNotificationQueueTaskData(newIdentifier, givenDeviceToken, dateUtilStub.givenDate))
    }

    @Test
    fun identify_givenNotFirstTimeIdentify_givenNoDeviceTokenRegistered_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val givenIdentifier = String.random
        val newIdentifier = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)
        whenever(backgroundQueueMock.addTask(any(), any())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.IdentifyProfile, IdentifyProfileQueueTaskData(newIdentifier, givenAttributes))
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.DeletePushToken), any())
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.RegisterDeviceToken), any())
    }

    @Test
    fun identify_givenNotFirstTimeIdentify_givenDeviceTokenExists_expectIdentifyBackgroundQueue_expectDeleteToken_expectRegisterDeviceToken() {
        val givenIdentifier = String.random
        val newIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(backgroundQueueMock.addTask(any(), any())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.identify(newIdentifier, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.IdentifyProfile, IdentifyProfileQueueTaskData(newIdentifier, givenAttributes))
        verify(backgroundQueueMock).addTask(QueueTaskType.DeletePushToken, DeletePushNotificationQueueTaskData(givenIdentifier, givenDeviceToken))
        verify(backgroundQueueMock).addTask(QueueTaskType.RegisterDeviceToken, RegisterPushNotificationQueueTaskData(newIdentifier, givenDeviceToken, dateUtilStub.givenDate))
    }

    @Test
    fun identify_givenIdentifyAlreadyIdentifiedProfile_expectIdentifyBackgroundQueue_expectDoNotDeleteToken_expectDoNotRegisterToken() {
        val givenIdentifier = String.random
        val givenDeviceToken = String.random
        val givenAttributes = mapOf("name" to String.random)
        prefRepository.saveIdentifier(givenIdentifier)
        prefRepository.saveDeviceToken(givenDeviceToken)
        whenever(backgroundQueueMock.addTask(any(), any())).thenReturn(QueueModifyResult(true, QueueStatus(siteId, 1)))

        customerIOClient.identify(givenIdentifier, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.IdentifyProfile, IdentifyProfileQueueTaskData(givenIdentifier, givenAttributes))
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.DeletePushToken), any())
        verify(backgroundQueueMock, never()).addTask(eq(QueueTaskType.RegisterDeviceToken), any())
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

        customerIOClient.registerDeviceToken(givenDeviceToken)

        verifyNoInteractions(backgroundQueueMock)
        prefRepository.getDeviceToken() shouldBeEqualTo givenDeviceToken
    }

    @Test
    fun registerDeviceToken_givenIdentifiedProfile_expectAddTaskToQueue_expectSaveToken() {
        val givenIdentifier = String.random
        val givenDeviceToken = String.random
        prefRepository.saveIdentifier(givenIdentifier)

        customerIOClient.registerDeviceToken(givenDeviceToken)

        verify(backgroundQueueMock).addTask(QueueTaskType.RegisterDeviceToken, RegisterPushNotificationQueueTaskData(givenIdentifier, givenDeviceToken, dateUtilStub.givenDate))
        prefRepository.getDeviceToken() shouldBeEqualTo givenDeviceToken
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

        verify(backgroundQueueMock).addTask(QueueTaskType.DeletePushToken, DeletePushNotificationQueueTaskData(givenIdentifier, givenDeviceToken))
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

        verify(backgroundQueueMock).addTask(QueueTaskType.TrackEvent, TrackEventQueueTaskData(givenTrackEventName, Event(givenTrackEventName, EventType.event, givenAttributes, dateUtilStub.givenDateMillis)))
    }
}
