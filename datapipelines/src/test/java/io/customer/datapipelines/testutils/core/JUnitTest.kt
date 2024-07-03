package io.customer.datapipelines.testutils.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.JUnit5Test
import io.customer.sdk.CustomerIO

/**
 * Extension of [JUnit5Test] that utilizes [UnitTestDelegate] to setup test environment
 * for running unit tests.
 * This class should be used for running unit tests using JUnit.
 */
abstract class JUnitTest : JUnit5Test() {
    private val delegate: UnitTestDelegate = UnitTestDelegate(applicationInstance = "Test")
    private val defaultTestConfiguration: DataPipelinesTestConfig = testConfiguration {}

    val sdkInstance: CustomerIO get() = delegate.sdkInstance
    val analytics: Analytics get() = delegate.analytics

    override fun setup(testConfig: TestConfig) {
        val config = defaultTestConfiguration + testConfig

        super.setup(config)
        delegate.setup(config)
    }

    override fun teardown() {
        delegate.teardownSDKComponent()

        super.teardown()
    }
}
