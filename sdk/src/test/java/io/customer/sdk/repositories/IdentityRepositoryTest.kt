package io.customer.sdk.repositories

import io.customer.base.error.StatusCode
import io.customer.sdk.api.service.CustomerIOService
import io.customer.sdk.repository.AttributesRepository
import io.customer.sdk.repository.IdentityRepository
import io.customer.sdk.repository.IdentityRepositoryImpl
import io.customer.sdk.utils.MockRetrofitError
import io.customer.sdk.utils.MockRetrofitSuccess
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

internal class IdentityRepositoryTest {

    private val mockCustomerIOService: CustomerIOService = mock()
    private val mockAttributesRepository: AttributesRepository = mock()

    lateinit var identityRepository: IdentityRepository

    @Before
    fun setup() {
        identityRepository = IdentityRepositoryImpl(
            customerService = mockCustomerIOService,
            attributesRepository = mockAttributesRepository
        )
    }

    @Test
    fun `Return success action when identify api request returns success`() {
        Mockito.`when`(
            mockCustomerIOService.identifyCustomer(any(), any())
        ).thenReturn(MockRetrofitSuccess(Unit).toCustomerIoCall())

        val result = identityRepository.identify("test", emptyMap()).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Return success action when identify api request with attributes returns success`() {
        Mockito.`when`(
            mockCustomerIOService.identifyCustomer(any(), any())
        ).thenReturn(MockRetrofitSuccess(Unit).toCustomerIoCall())

        val result = identityRepository.identify("test", mapOf("email" to "test")).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Return error action identify api request returns 500 error`() {
        Mockito.`when`(
            mockCustomerIOService.identifyCustomer(any(), any())
        ).thenReturn(MockRetrofitError<Unit>(500).toCustomerIoCall())

        val result = identityRepository.identify("test", emptyMap()).execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `Return error action identify api request returns 400 error`() {
        Mockito.`when`(
            mockCustomerIOService.identifyCustomer(any(), any())
        ).thenReturn(MockRetrofitError<Unit>(400).toCustomerIoCall())

        val result = identityRepository.identify("test", emptyMap()).execute()

        verifyError(result, StatusCode.BadRequest)
    }
}
