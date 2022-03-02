package io.customer.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.common_test.BaseTest
import io.customer.common_test.DateUtilStub
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Event
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.util.Logger
import io.customer.sdk.utils.random
import io.customer.sdk.hooks.HooksManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class CustomerIOClientTest : BaseTest() {

    private val preferenceRepository: PreferenceRepository = mock()
    private val backgroundQueueMock: Queue = mock()
    private val hooksMock: HooksManager = mock()
    private val dateUtilMock = DateUtilStub()
    private val loggerMock: Logger = mock()

    private lateinit var customerIOClient: CustomerIOClient

    @Before
    override fun setup() {
        super.setup()

        customerIOClient = CustomerIOClient(
            preferenceRepository = preferenceRepository,
            backgroundQueue = backgroundQueueMock,
            hooks = hooksMock,
            dateUtil = dateUtilMock,
            logger = loggerMock
        )
    }

    @Test
    fun verifyWhenCustomerIdentifyIsClearedItsRemovedInPrefsRepo() {
        `when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        customerIOClient.clearIdentify()

        verify(preferenceRepository, times(1)).removeIdentifier("identify")
    }

    @Test
    fun track_givenNoProfileIdentified_expectDoNotAddTaskBackgroundQueue() {
        whenever(preferenceRepository.getIdentifier()).thenReturn(null)

        customerIOClient.track(EventType.event, String.random, emptyMap())

        verifyNoInteractions(backgroundQueueMock)
    }

    @Test
    fun track_givenProfileIdentified_expectAddTaskBackgroundQueue() {
        val givenIdentifier = String.random
        val givenTrackEventName = String.random
        val givenAttributes = mapOf("foo" to String.random)
        whenever(preferenceRepository.getIdentifier()).thenReturn(givenIdentifier)

        customerIOClient.track(EventType.event, givenTrackEventName, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.TrackEvent, TrackEventQueueTaskData(givenTrackEventName, Event(givenTrackEventName, EventType.event, givenAttributes, dateUtilMock.givenDateMillis)))
    }
}
