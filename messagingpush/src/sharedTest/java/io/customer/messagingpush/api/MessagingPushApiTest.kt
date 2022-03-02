package io.customer.messagingpush.api

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.common_test.DateUtilStub
import io.customer.messagingpush.queue.taskdata.DeletePushNotificationQueueTaskData
import io.customer.messagingpush.queue.taskdata.RegisterPushNotificationQueueTaskData
import io.customer.messagingpush.queue.type.QueueTaskType
import io.customer.sdk.queue.Queue
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.utils.random
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class MessagingPushApiTest : BaseTest() {

    private val preferenceRepositoryMock: PreferenceRepository = mock()
    private val queueMock: Queue = mock()
    private val dateUtilMock = DateUtilStub()

    private lateinit var api: MessagingPushApi

    @Before
    override fun setup() {
        super.setup()

        api = MessagingPushApiImpl(di.logger, preferenceRepositoryMock, queueMock, dateUtilMock)
    }

    @Test
    fun registerDeviceToken_givenNoIdentifiedProfile_expectDoNotAddTaskToBackgroundQueue() {
        whenever(preferenceRepositoryMock.getIdentifier()).thenReturn(null)

        api.registerDeviceToken(String.random)

        verifyNoInteractions(queueMock)
    }

    @Test
    fun registerDeviceToken_givenIdentifiedProfile_expectAddTaskToQueue() {
        val givenIdentifier = String.random
        val givenDeviceToken = String.random
        whenever(preferenceRepositoryMock.getIdentifier()).thenReturn(givenIdentifier)

        api.registerDeviceToken(givenDeviceToken)

        verify(queueMock).addTask(QueueTaskType.RegisterDeviceToken, RegisterPushNotificationQueueTaskData(givenIdentifier, givenDeviceToken, dateUtilMock.givenDate))
    }

    @Test
    fun deleteDeviceToken_givenNoDeviceToken_expectDoNotAddTaskToBackgroundQueue() {
        whenever(preferenceRepositoryMock.getDeviceToken()).thenReturn(null)
        whenever(preferenceRepositoryMock.getIdentifier()).thenReturn(String.random)

        api.deleteDeviceToken()

        verifyNoInteractions(queueMock)
    }

    @Test
    fun deleteDeviceToken_givenNoProfileIdentified_expectDoNotAddTaskToBackgroundQueue() {
        whenever(preferenceRepositoryMock.getDeviceToken()).thenReturn(String.random)
        whenever(preferenceRepositoryMock.getIdentifier()).thenReturn(null)

        api.deleteDeviceToken()

        verifyNoInteractions(queueMock)
    }

    @Test
    fun deleteDeviceToken_givenDeviceTokenAndIdentifiedProfile_expectAddTaskToBackgroundQueue() {
        val givenDeviceToken = String.random
        val givenIdentifier = String.random
        whenever(preferenceRepositoryMock.getDeviceToken()).thenReturn(givenDeviceToken)
        whenever(preferenceRepositoryMock.getIdentifier()).thenReturn(givenIdentifier)

        api.deleteDeviceToken()

        verify(queueMock).addTask(QueueTaskType.DeletePushToken, DeletePushNotificationQueueTaskData(givenIdentifier, givenDeviceToken))
    }
}
