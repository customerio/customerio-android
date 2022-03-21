package io.customer.sdk.api.interceptors

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.MockCustomerIOBuilder
import io.customer.sdk.data.store.DeviceStore
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
internal class HeadersInterceptorTest {

    private lateinit var headersInterceptor: HeadersInterceptor

    private val deviceStoreMock: DeviceStore = mock()
    private val configMock: CustomerIOConfig
        get() = MockCustomerIOBuilder().build().config

    @Before
    fun setup() {
        headersInterceptor = HeadersInterceptor(deviceStoreMock, configMock)
    }

    @Test
    fun getHeaders_givenUserAgentWithInvalidCharacters_expectFilteredHeaderValue() {
        val given = "CIO/1.0.0 AppName(오)/2.2.3"
        val expected = "CIO/1.0.0 AppName()/2.2.3"
        whenever(deviceStoreMock.buildUserAgent()).thenReturn(given)

        val actual = headersInterceptor.getHeaders().find { it.first == "User-Agent" }!!.second

        actual shouldBeEqualTo expected
    }

    @Test
    fun getHeaders_givenUserAgentWithAllValidCharacters_expectUnfilteredHeaderValue() {
        val expected = "CIO/1.0.0 AppName/2.2.3"
        whenever(deviceStoreMock.buildUserAgent()).thenReturn(expected)

        val actual = headersInterceptor.getHeaders().find { it.first == "User-Agent" }!!.second

        actual shouldBeEqualTo expected
    }
}
