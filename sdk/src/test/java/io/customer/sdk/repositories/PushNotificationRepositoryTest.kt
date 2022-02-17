package io.customer.sdk.repositories

import io.customer.base.error.StatusCode
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.api.service.PushService
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.PushNotificationRepository
import io.customer.sdk.repository.PushNotificationRepositoryImp
import io.customer.sdk.testutils.MockRetrofitError
import io.customer.sdk.testutils.MockRetrofitSuccess
import io.customer.sdk.testutils.verifyError
import io.customer.sdk.testutils.verifySuccess
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

internal class PushNotificationRepositoryTest {

    private val mockCustomerService: CustomerService = mock()
    private val mockPushService: PushService = mock()

    private lateinit var pushNotificationRepository: PushNotificationRepository

    @Before
    fun setup() {
        pushNotificationRepository = PushNotificationRepositoryImp(
            customerService = mockCustomerService,
            pushService = mockPushService
        )
    }

    @Test
    fun `Register device returns error if user is not identified`() {

        val result = pushNotificationRepository.registerDeviceToken(null, "token").execute()

        verifyError(result, StatusCode.UnIdentifiedUser)
    }

    @Test
    fun `Register device returns error if token is blank`() {

        val result = pushNotificationRepository.registerDeviceToken("identifier", "").execute()

        verifyError(result, StatusCode.InvalidToken)
    }

    @Test
    fun `Register device returns success if add device api returns success`() {
        Mockito.`when`(
            mockCustomerService.addDevice(any(), any())
        ).thenReturn(MockRetrofitSuccess(Unit).toCustomerIoCall())

        val result = pushNotificationRepository.registerDeviceToken("identifier", "token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Register device returns error if add device api returns error`() {
        Mockito.`when`(
            mockCustomerService.addDevice(any(), any())
        ).thenReturn(MockRetrofitError<Unit>(500).toCustomerIoCall())

        val result = pushNotificationRepository.registerDeviceToken("identifier", "token").execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `Delete device method returns success if user is not identified`() {

        // no device token, delete has already happened or is not needed
        val result = pushNotificationRepository.deleteDeviceToken(null, "token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Delete device method returns success if token is blank`() {

        // no customer identified, we can safely clear the device token
        val result = pushNotificationRepository.deleteDeviceToken("identifier", "").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Delete device method returns success if delete device api returns success`() {
        Mockito.`when`(
            mockCustomerService.removeDevice(any(), any())
        ).thenReturn(MockRetrofitSuccess(Unit).toCustomerIoCall())

        val result = pushNotificationRepository.deleteDeviceToken("identifier", "token").execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Delete device method returns returns error if add device api returns error`() {
        Mockito.`when`(
            mockCustomerService.removeDevice(any(), any())
        ).thenReturn(MockRetrofitError<Unit>(500).toCustomerIoCall())

        val result = pushNotificationRepository.deleteDeviceToken("identifier", "token").execute()

        verifyError(result, StatusCode.InternalServerError)
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
