package io.customer.sdk

import io.customer.base.data.ErrorResult
import io.customer.base.error.ErrorDetail
import io.customer.base.error.StatusCode
import io.customer.base.utils.ActionUtils.Companion.getEmptyAction
import io.customer.base.utils.ActionUtils.Companion.getErrorAction
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
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
    fun `Identify request return success response`() {
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
    fun `Identify request return error response`() {
        Mockito.`when`(
            mockCustomerIO.api.identify(
                identifier = any(),
                attributes = any()
            )
        )
            .thenReturn(getErrorAction(errorResult = ErrorResult(error = ErrorDetail(statusCode = StatusCode.InternalServerError))))

        val response = customerIO.identify("test-identifier").execute()

        verifyError(response, StatusCode.InternalServerError)
    }
}
