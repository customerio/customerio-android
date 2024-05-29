package io.customer.datapipelines.support.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.sdk.CustomerIOBuilder

/**
 * Test configuration for setting up the CustomerIO SDK and analytics instance.
 *
 * @property cdpApiKey CDP API key to be used for testing
 * @property sdkConfig used to configure the CustomerIO SDK instance for each test separately
 * @property configurePlugins used to configure plugins for analytics instance before
 * we add more plugins to it in CustomerIO initialization
 */
class TestConfiguration private constructor(
    val cdpApiKey: String,
    val sdkConfig: CustomerIOBuilder.() -> Unit,
    val configurePlugins: Analytics.() -> Unit
) {
    class Builder {
        private var cdpApiKey: String = TEST_CDP_API_KEY
        private var sdkConfig: CustomerIOBuilder.() -> Unit = {}
        private var configurePlugins: Analytics.() -> Unit = {}

        fun cdpApiKey(cdpApiKey: String) {
            this.cdpApiKey = cdpApiKey
        }

        fun sdkConfig(block: CustomerIOBuilder.() -> Unit) {
            sdkConfig = block
        }

        fun configurePlugins(block: Analytics.() -> Unit) {
            configurePlugins = block
        }

        fun build(): TestConfiguration = TestConfiguration(
            cdpApiKey = cdpApiKey,
            sdkConfig = sdkConfig,
            configurePlugins = configurePlugins
        )

        companion object {
            const val TEST_CDP_API_KEY = "TESTING_API_KEY"
        }
    }
}

/**
 * Creates a [TestConfiguration] using DSL builder
 *
 * @param block Configuration block for [TestConfiguration.Builder].
 */
fun testConfiguration(block: TestConfiguration.Builder.() -> Unit): TestConfiguration {
    return TestConfiguration.Builder().apply(block).build()
}
