package io.customer.datapipelines.testutils.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.core.TestConstants
import io.customer.sdk.CustomerIOBuilder

/**
 * Test configuration for setting up the CustomerIO SDK and analytics instance.
 *
 * @property cdpApiKey CDP API key to be used for testing
 * @property sdkConfig used to configure the CustomerIO SDK instance for each test separately
 * @property configurePlugins used to configure plugins for analytics instance before
 * we add more plugins to it in CustomerIO initialization
 */
class DataPipelinesTestConfig private constructor(
    val cdpApiKey: String,
    val sdkConfig: CustomerIOBuilder.() -> Unit,
    val configurePlugins: Analytics.() -> Unit
) {
    class Builder {
        private var cdpApiKey: String = TestConstants.Keys.CDP_API_KEY
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

        fun build(): DataPipelinesTestConfig = DataPipelinesTestConfig(
            cdpApiKey = cdpApiKey,
            sdkConfig = sdkConfig,
            configurePlugins = configurePlugins
        )
    }
}

/**
 * Creates [DataPipelinesTestConfig] using DSL-like option for convenient initialization
 *
 * @param block Configuration block for [DataPipelinesTestConfig.Builder].
 */
fun testConfiguration(block: DataPipelinesTestConfig.Builder.() -> Unit): DataPipelinesTestConfig {
    return DataPipelinesTestConfig.Builder().apply(block).build()
}
