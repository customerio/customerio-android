package io.customer.sdk.repositories

import io.customer.base.error.StatusCode
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.api.service.PushService
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.PushNotificationRepository
import io.customer.sdk.repository.PushNotificationRepositoryImp
import io.customer.common_test.MockRetrofitError
import io.customer.common_test.MockRetrofitSuccess
import io.customer.common_test.verifyError
import io.customer.common_test.verifySuccess
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

internal class PushNotificationRepositoryTest {

    private val mockCustomerIOService: CustomerIOService = mock()
    private val mockPushService: PushService = mock()

    private lateinit var pushNotificationRepository: PushNotificationRepository

    @Before
    fun setup() {
        pushNotificationRepository = PushNotificationRepositoryImp(
            customerIOService = mockCustomerIOService,
            pushService = mockPushService
        )
    }

    @Test
    fun `Verify trackMetric returns 400 Bad request if required fields are empty`() {
        val result =
            pushNotificationRepository.trackMetric("", MetricEvent.opened, "token")
                .execute()

        verifyError(result, StatusCode.BadRequest)

        val result2 =
            pushNotificationRepository.trackMetric("delivery-id", MetricEvent.opened, "")
                .execute()

        verifyError(result2, StatusCode.BadRequest)
    }

    @Test
    fun `Verify trackMetric returns success if api returns success`() {
        Mockito.`when`(
            mockPushService.trackMetric(any())
        ).thenReturn(MockRetrofitSuccess(Unit).toCustomerIoCall())

        val result =
            pushNotificationRepository.trackMetric("delivery-id", MetricEvent.opened, "token")
                .execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Verify trackMetric returns error if api returns error`() {
        Mockito.`when`(
            mockPushService.trackMetric(any())
        ).thenReturn(MockRetrofitError<Unit>(500).toCustomerIoCall())

        val result =
            pushNotificationRepository.trackMetric("delivery-id", MetricEvent.opened, "token")
                .execute()

        verifyError(result, StatusCode.InternalServerError)
    }
}
