package io.customer.sdk

import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.utils.ActionUtils.Companion.getEmptyAction
import io.customer.base.utils.ActionUtils.Companion.getErrorAction
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.amshove.kluent.`should be equal to`
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

internal class CustomerIOTest {

    private lateinit var customerIO: CustomerIO
    lateinit var mockCustomerIO: MockCustomerIOBuilder

    @Before
    fun setUp() {
        mockCustomerIO = MockCustomerIOBuilder()
        customerIO = mockCustomerIO.build()
    }

    @Test
    fun `verify SDK configuration is correct`() {
        customerIO.config.siteId `should be equal to` "mock-site"
        customerIO.config.apiKey `should be equal to` "mock-key"
        customerIO.config.timeout `should be equal to` 6000
        customerIO.config.region `should be equal to` Region.US
        customerIO.config.urlHandler `should be equal to` null
    }

    @Test
    fun `verify SDK returns success when customer is identified`() {
        Mockito.`when`(
            mockCustomerIO.api.identify(
                identifier = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.identify("test-identifier").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when customer identify request fails`() {
        Mockito.`when`(
            mockCustomerIO.api.identify(
                identifier = any(),
                attributes = any()
            )
        ).thenReturn(
            getErrorAction(
                errorResult = ErrorResult(
                    error =
                    ErrorDetail(statusCode = StatusCode.InternalServerError)
                )
            )
        )

        val response = customerIO.identify("test-identifier").execute()

        verifyError(response, StatusCode.InternalServerError)
    }

    @Test
    fun `verify SDK returns success when event is tracked`() {
        Mockito.`when`(
            mockCustomerIO.api.track(
                name = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.track("event_name", mapOf("key" to "value")).execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when event tracking request fails`() {
        Mockito.`when`(
            mockCustomerIO.api.track(
                name = any(),
                attributes = any()
            )
        ).thenReturn(
            getErrorAction(
                errorResult = ErrorResult(
                    error =
                    ErrorDetail(statusCode = StatusCode.InternalServerError)
                )
            )
        )

        val response = customerIO.track("event_name", mapOf("key" to "value")).execute()

        verifyError(response, StatusCode.InternalServerError)
    }

    @Test
    fun `verify SDK returns success when device is added`() {
        Mockito.`when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when adding device request fails`() {
        Mockito.`when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
            )
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns error when device is removed`() {
        Mockito.`when`(
            mockCustomerIO.api.deleteDeviceToken()
        ).thenReturn(getEmptyAction())

        val response = customerIO.deleteDeviceToken().execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when removing device request fails`() {
        Mockito.`when`(
            mockCustomerIO.api.deleteDeviceToken()
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.deleteDeviceToken().execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns success when push event metric is tracked`() {
        Mockito.`when`(
            mockCustomerIO.api.trackMetric(any(), any(), any())
        ).thenReturn(getEmptyAction())

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when push event metric request fails`() {
        Mockito.`when`(
            mockCustomerIO.api.trackMetric(any(), any(), any())
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifyError(response, StatusCode.BadRequest)
    }
}
