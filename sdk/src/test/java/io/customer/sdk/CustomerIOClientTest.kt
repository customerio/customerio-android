package io.customer.sdk

import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.base.utils.ActionUtils
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.request.Event
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.queue.Queue
import io.customer.sdk.queue.taskdata.TrackEventQueueTaskData
import io.customer.sdk.queue.type.QueueTaskType
import io.customer.sdk.repository.IdentityRepository
import io.customer.sdk.repository.PreferenceRepository
import io.customer.sdk.repository.PushNotificationRepository
import io.customer.sdk.util.DateUtil
import io.customer.sdk.util.Logger
import io.customer.sdk.utils.random
import io.customer.common_test.verifyError
import io.customer.common_test.verifySuccess
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.*

internal class CustomerIOClientTest {

    private val preferenceRepository: PreferenceRepository = mock()
    private val identityRepository: IdentityRepository = mock()
    private val pushNotificationRepository: PushNotificationRepository = mock()
    private val backgroundQueueMock: Queue = mock()
    private val dateUtilMock: DateUtil = mock()
    private val loggerMock: Logger = mock()

    private lateinit var customerIOClient: CustomerIOClient

    @Before
    fun setup() {
        customerIOClient = CustomerIOClient(
            identityRepository = identityRepository,
            preferenceRepository = preferenceRepository,
            pushNotificationRepository = pushNotificationRepository,
            backgroundQueue = backgroundQueueMock,
            dateUtil = dateUtilMock,
            logger = loggerMock
        )
    }

    @Test
    fun `verify client sends error when identify repo fails identifying`() {
        `when`(identityRepository.identify(any(), any()))
            .thenReturn(
                ActionUtils.getErrorAction(
                    errorResult = ErrorResult(
                        error = ErrorDetail(
                            statusCode = StatusCode.InternalServerError
                        )
                    )
                )
            )

        val result = customerIOClient.identify("identifier", mapOf()).execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `verify client sends success when identify repo succeed in identifying`() {
        `when`(identityRepository.identify(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        val result = customerIOClient.identify("identifier", mapOf()).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify when customer is identified then identifier is saved in prefs repo`() {
        `when`(identityRepository.identify(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.identify("identifier", mapOf()).execute()

        verify(preferenceRepository, times(1)).saveIdentifier(any())
    }

    @Test
    fun `verify when customer identify is cleared its removed in prefs repo`() {
        `when`(preferenceRepository.getIdentifier()).thenReturn("identify")

        customerIOClient.clearIdentify()

        verify(preferenceRepository, times(1)).removeIdentifier("identify")
    }

    @Test
    fun `verify client sends success when customer device is added`() {
        `when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        `when`(pushNotificationRepository.registerDeviceToken(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        val result = customerIOClient.registerDeviceToken("token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `verify when customer device is added then token is saved`() {
        `when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        `when`(pushNotificationRepository.registerDeviceToken(any(), any()))
            .thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.registerDeviceToken("token").execute()

        verify(preferenceRepository, times(1)).saveDeviceToken("token")
    }

    @Test
    fun `verify when customer device is removed then token is removed`() {
        `when`(
            preferenceRepository.getIdentifier()
        ).thenReturn("identify")

        `when`(preferenceRepository.getDeviceToken()).thenReturn("token")

        `when`(
            pushNotificationRepository.deleteDeviceToken(any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        customerIOClient.deleteDeviceToken().execute()

        verify(preferenceRepository, times(1)).removeDeviceToken("token")
    }

    @Test
    fun `verify client sends error when push repo fails in tracking push metric`() {
        `when`(
            pushNotificationRepository.trackMetric(any(), any(), any())
        ).thenReturn(
            ActionUtils.getErrorAction(
                errorResult = ErrorResult(
                    error = ErrorDetail(
                        statusCode = StatusCode.InternalServerError
                    )
                )
            )
        )

        val result =
            customerIOClient.trackMetric("delivery-id", MetricEvent.converted, "token").execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `verify client sends success when repo succeed in tracking push metric`() {
        `when`(
            pushNotificationRepository.trackMetric(any(), any(), any())
        ).thenReturn(ActionUtils.getEmptyAction())

        val result =
            customerIOClient.trackMetric("delivery-id", MetricEvent.converted, "token").execute()

        verifySuccess(result, Unit)
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
        val givenDateNow = Date().getUnixTimestamp()
        whenever(dateUtilMock.nowUnixTimestamp).thenReturn(givenDateNow)
        whenever(preferenceRepository.getIdentifier()).thenReturn(givenIdentifier)

        customerIOClient.track(EventType.event, givenTrackEventName, givenAttributes)

        verify(backgroundQueueMock).addTask(QueueTaskType.TrackEvent.name, TrackEventQueueTaskData(givenTrackEventName, Event(givenTrackEventName, EventType.event, givenAttributes, givenDateNow)))
    }
}
