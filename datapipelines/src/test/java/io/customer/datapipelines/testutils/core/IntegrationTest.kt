package io.customer.datapipelines.testutils.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.CustomerIO

/**
 * Extension of [RobolectricTest] that utilizes [UnitTestDelegate] to setup test environment.
 * This class should be used for running integration tests using Robolectric.
 */
abstract class IntegrationTest : RobolectricTest() {
    private val delegate: UnitTestDelegate = UnitTestDelegate(applicationMock)
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
