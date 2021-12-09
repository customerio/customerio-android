package io.customer.sdk.repositories

import io.customer.base.error.StatusCode
import io.customer.sdk.api.service.CustomerService
import io.customer.sdk.data.model.EventType
import io.customer.sdk.data.moshi.CustomerIOParser
import io.customer.sdk.data.moshi.CustomerIOParserImpl
import io.customer.sdk.repository.AttributesRepository
import io.customer.sdk.repository.MoshiAttributesRepositoryImp
import io.customer.sdk.repository.TrackingRepository
import io.customer.sdk.repository.TrackingRepositoryImp
import io.customer.sdk.utils.MockRetrofitError
import io.customer.sdk.utils.MockRetrofitSuccess
import io.customer.sdk.utils.verifyError
import io.customer.sdk.utils.verifySuccess
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

internal class TrackingRepositoryTest {

    lateinit var trackingRepository: TrackingRepository
    private val parser: CustomerIOParser = CustomerIOParserImpl()
    lateinit var attributesRepository: AttributesRepository
    private val mockCustomerService: CustomerService = mock()

    @Before
    fun setup() {
        attributesRepository = MoshiAttributesRepositoryImp(parser)
        trackingRepository = TrackingRepositoryImp(
            customerService = mockCustomerService,
            attributesRepository = attributesRepository,
        )
    }

    @Test
    fun `Unverified user error thrown when identifier is null`() {

        val result =
            trackingRepository.track(
                identifier = null,
                type = EventType.event,
                name = "event", attributes = emptyMap()
            )
                .execute()

        verifyError(result, StatusCode.UnIdentifiedUser)
    }

    @Test
    fun `Return success action when track api request with attributes returns success`() {
        Mockito.`when`(
            mockCustomerService.track(any(), any())
        ).thenReturn(MockRetrofitSuccess(Unit).toCustomerIoCall())

        val result = trackingRepository.track(
            identifier = "identifier",
            type = EventType.event,
            name = "event",
            attributes = emptyMap()
        ).execute()

        verifySuccess(result, Unit)
    }

    @Test
    fun `Return error action when track api request returns 500 error`() {
        Mockito.`when`(
            mockCustomerService.track(any(), any())
        ).thenReturn(MockRetrofitError<Unit>(500).toCustomerIoCall())

        val result = trackingRepository.track(
            identifier = "identifier",
            type = EventType.event,
            name = "event",
            attributes = emptyMap()
        ).execute()

        verifyError(result, StatusCode.InternalServerError)
    }

    @Test
    fun `Return error action when track api request returns 400 error`() {
        Mockito.`when`(
            mockCustomerService.track(any(), any())
        ).thenReturn(MockRetrofitError<Unit>(400).toCustomerIoCall())

        val result = trackingRepository.track(
            identifier = "identifier",
            type = EventType.screen,
            name = "event",
            attributes = mapOf("key" to Unit)
        ).execute()

        verifyError(result, StatusCode.BadRequest)
    }
}
