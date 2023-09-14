package io.customer.commontest

import android.app.Application
import android.content.Context
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.extensions.random
import org.mockito.kotlin.mock

abstract class BaseUnitTest : BaseTest() {

    override val context: Context
        get() = mock()

    override val application: Application
        get() = mock()

    override fun setup(cioConfig: CustomerIOConfig) {
        super.setup(cioConfig)

        CustomerIO.Builder(
            siteId = siteId,
            apiKey = String.random,
            region = Region.US,
            appContext = application
        ).apply {
            overrideDiGraph = di
        }.build()
    }
}
