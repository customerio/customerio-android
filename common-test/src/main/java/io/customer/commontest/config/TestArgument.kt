package io.customer.commontest.config

import android.app.Application
import io.customer.sdk.data.store.Client

sealed class TestArgument {
    data class ApplicationConfig(
        val value: Application
    ) : TestArgument()

    data class ClientConfig(
        val value: Client = Client.Android(sdkVersion = "3.0.0")
    ) : TestArgument()
}
