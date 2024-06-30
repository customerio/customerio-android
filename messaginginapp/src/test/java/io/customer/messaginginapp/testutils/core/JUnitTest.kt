package io.customer.messaginginapp.testutils.core

import io.customer.commontest.config.TestArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.JUnit5Test
import io.customer.messaginginapp.testutils.extension.mockAndroidLog

abstract class JUnitTest : JUnit5Test() {
    private val defaultTestConfiguration: TestConfig = testConfigurationDefault {
        argument(TestArgument.ApplicationConfig(applicationMock))
        argument(TestArgument.ClientConfig())

        diGraph {
            sdk {
                // Because we are calling clearAllMocks() in teardown() method, we cannot use
                // BeforeAll annotations for static override and we have to call mockAndroidLog()
                // in setup() method
                mockAndroidLog()
            }
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(defaultTestConfiguration + testConfig)
    }
}
