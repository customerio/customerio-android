package io.customer.messagingpush.testutils.core

import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.testConfigurationDefault
import io.customer.commontest.core.JUnit5Test

abstract class JUnitTest : JUnit5Test() {
    private val defaultTestConfiguration: TestConfig = testConfigurationDefault {
        diGraph {
            sdk { registerAndroidSDKComponent() }
        }
    }

    override fun setup(testConfig: TestConfig) {
        super.setup(defaultTestConfiguration + testConfig)
    }
}
