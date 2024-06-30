package io.customer.commontest.core

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.sdk.core.di.SDKComponent
import io.mockk.clearAllMocks

abstract class BaseTest {
    protected open fun setup(testConfig: TestConfig = testConfigurationDefault { }) {
        testConfig.diGraph.sdkComponent.invoke(SDKComponent)
    }

    protected open fun teardown() {
        clearAllComponents()
    }

    protected open fun clearAllComponents() {
        SDKComponent.reset()
        clearAllMocks()
    }
}
