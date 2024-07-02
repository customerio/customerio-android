package io.customer.commontest.core

import io.customer.commontest.config.TestArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.argumentOrNull
import io.customer.commontest.config.testConfigurationDefault
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.mockk.clearAllMocks

abstract class BaseTest {
    protected open fun setup(testConfig: TestConfig = testConfigurationDefault { }) {
        testConfig.diGraph.sdkComponent.invoke(SDKComponent)
        registerAndroidSDKComponent(testConfig)
    }

    protected open fun teardown() {
        clearAllComponents()
    }

    protected open fun clearAllComponents() {
        SDKComponent.reset()
        clearAllMocks()
    }

    private fun registerAndroidSDKComponent(testConfig: TestConfig) {
        val application = testConfig.argumentOrNull<TestArgument.ApplicationConfig>()?.value ?: return
        val client = testConfig.argumentOrNull<TestArgument.ClientConfig>()?.value ?: return
        // Because we are not initializing the SDK, we need to register the
        // Android SDK component manually so that the module can utilize it
        testConfig.diGraph.androidSDKComponent(SDKComponent.registerAndroidSDKComponent(application, client))
    }
}
