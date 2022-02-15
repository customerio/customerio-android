package io.customer.sdk

import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.utils.ActionUtils.Companion.getEmptyAction
import io.customer.base.utils.ActionUtils.Companion.getErrorAction
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
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
        customerIO.config.siteId shouldBeEqualTo MockCustomerIOBuilder.siteId
        customerIO.config.apiKey shouldBeEqualTo MockCustomerIOBuilder.apiKey
        customerIO.config.timeout shouldBeEqualTo MockCustomerIOBuilder.timeout.toLong()
        customerIO.config.region shouldBeEqualTo MockCustomerIOBuilder.region
        customerIO.config.urlHandler shouldBeEqualTo MockCustomerIOBuilder.urlHandler
        customerIO.config.autoTrackScreenViews shouldBeEqualTo MockCustomerIOBuilder.shouldAutoRecordScreenViews
    }

    @Test
    fun `verify SDK returns success when customer is identified`() {
        `when`(
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
        `when`(
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
        `when`(
            mockCustomerIO.api.track(
                name = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.track("event_name", mapOf("key" to "value")).execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns success when screen is tracked`() {
        `when`(
            mockCustomerIO.api.screen(
                name = any(),
                attributes = any()
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.screen("Login", mapOf("key" to "value")).execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when event tracking request fails`() {
        `when`(
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
    fun `verify SDK returns error when screen tracking request fails`() {
        `when`(
            mockCustomerIO.api.screen(
                name = any(),
                attributes = any()
            )
        ).thenReturn(
            getErrorAction(
                errorResult = ErrorResult(
                    error =
                    ErrorDetail(statusCode = StatusCode.BadRequest)
                )
            )
        )

        val response = customerIO.screen("Login", emptyMap()).execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns success when device is added`() {
        `when`(
            mockCustomerIO.api.registerDeviceToken(
                any(),
            )
        ).thenReturn(getEmptyAction())

        val response = customerIO.registerDeviceToken("event_name").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when adding device request fails`() {
        `when`(
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
        `when`(
            mockCustomerIO.api.deleteDeviceToken()
        ).thenReturn(getEmptyAction())

        val response = customerIO.deleteDeviceToken().execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when removing device request fails`() {
        `when`(
            mockCustomerIO.api.deleteDeviceToken()
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response = customerIO.deleteDeviceToken().execute()

        verifyError(response, StatusCode.BadRequest)
    }

    @Test
    fun `verify SDK returns success when push event metric is tracked`() {
        `when`(
            mockCustomerIO.api.trackMetric(any(), any(), any())
        ).thenReturn(getEmptyAction())

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifySuccess(response, Unit)
    }

    @Test
    fun `verify SDK returns error when push event metric request fails`() {
        `when`(
            mockCustomerIO.api.trackMetric(any(), any(), any())
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.BadRequest))))

        val response =
            customerIO.trackMetric("delivery_id", MetricEvent.delivered, "token").execute()

        verifyError(response, StatusCode.BadRequest)
    }
}
