package io.customer.messaginginapp.testutils.core

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.ClientArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.RobolectricTest

abstract class IntegrationTest : RobolectricTest() {
    private val defaultTestConfiguration: TestConfig = testConfigurationDefault {
        argument(ApplicationArgument(applicationMock))
        argument(ClientArgument())
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(defaultTestConfiguration + testConfig)
    }
}
