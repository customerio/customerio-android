package io.customer.tracking.migration.testutils.core

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.ClientArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.JUnit5Test

abstract class JUnitTest : JUnit5Test() {
    private val defaultTestConfiguration: TestConfig = testConfigurationDefault {
        argument(ApplicationArgument(applicationMock))
        argument(ClientArgument())
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(defaultTestConfiguration + testConfig)
    }
}
