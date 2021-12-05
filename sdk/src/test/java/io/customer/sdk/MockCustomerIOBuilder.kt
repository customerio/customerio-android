package io.customer.sdk

import io.customer.sdk.api.CustomerIoApi
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.CustomerIOStore
import org.mockito.kotlin.mock

internal class MockCustomerIOBuilder {

    lateinit var api: CustomerIoApi
    lateinit var store: CustomerIOStore
    private lateinit var customerIO: CustomerIO

    companion object {
        const val apiKey = "mock-key"
        const val siteId = "mock-site"
        val region = Region.US
        const val timeout = 6000
        val urlHandler = null
    }

    fun build(): CustomerIO {
        val customerIOConfig = CustomerIOConfig(
            apiKey = "mock-key",
            siteId = "mock-site",
            region = Region.US,
            timeout = 6000,
            urlHandler = null
        )

        api = mock()
        store = mock()

        customerIO = CustomerIO(
            config = customerIOConfig,
            api = api,
            store = store
        )

        return customerIO
    }
}
