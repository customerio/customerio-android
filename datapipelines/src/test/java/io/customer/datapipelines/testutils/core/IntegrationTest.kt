package io.customer.datapipelines.testutils.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.RobolectricTest
import io.customer.commontest.core.TestConstants.TEST_CDP_API_KEY
import io.customer.sdk.CustomerIO

/**
 * Extension of the [UnitTestDelegate] class to provide setup and teardown methods for
 * Robolectric tests. Since Robolectric is not compatible with JUnit 5, this class
 * uses JUnit 4 annotations to provide setup and teardown methods for the test environment.
 * The class uses mock application instance to allow running tests using Robolectric API for Android.
 */
abstract class IntegrationTest : RobolectricTest() {
    private val delegate: UnitTestDelegate = UnitTestDelegate(applicationMock)
    private val defaultTestConfiguration: DataPipelinesTestConfig = testConfiguration {
        cdpApiKey(TEST_CDP_API_KEY)
    }

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
