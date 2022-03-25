package io.customer.sdk

import io.customer.sdk.api.CustomerIOApi
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.CustomerIOStore
import org.mockito.kotlin.mock

internal class MockCustomerIOBuilder(private val customerIOConfig: CustomerIOConfig = defaultConfig) {

    lateinit var api: CustomerIOApi
    lateinit var store: CustomerIOStore
    private lateinit var customerIO: CustomerIO

    companion object {
        const val apiKey = "mock-key"
        const val siteId = "mock-site"
        val region = Region.US
        const val timeout = 6000
        val urlHandler = null
        const val shouldAutoRecordScreenViews = false
        const val autoTrackDeviceAttributes = true

        val defaultConfig = CustomerIOConfig(
            apiKey = "mock-key",
            siteId = "mock-site",
            region = Region.US,
            timeout = 6000,
            urlHandler = urlHandler,
            autoTrackScreenViews = shouldAutoRecordScreenViews,
            autoTrackDeviceAttributes = autoTrackDeviceAttributes
        )
    }

    fun build(): CustomerIO {

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
