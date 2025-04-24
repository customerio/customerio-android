package io.customer.datapipelines.testutils.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.config.AnalyticsDSL
import io.customer.commontest.config.ConfigDSL
import io.customer.commontest.config.DIGraphConfiguration
import io.customer.commontest.config.TestArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.TestConfigBuilder
import io.customer.commontest.config.plus
import io.customer.commontest.core.TestConstants
import io.customer.sdk.CustomerIOBuilder

/**
 * Test configuration for setting up the CustomerIO SDK and analytics instance.
 *
 * @property cdpApiKey CDP API key to be used for testing
 * @property sdkConfig used to configure the CustomerIO SDK instance for each test separately
 * @property analytics used to configure plugins for analytics instance before
 * we add more plugins to it in CustomerIO initialization
 */
class DataPipelinesTestConfig private constructor(
    override val arguments: List<TestArgument>,
    override val diGraph: DIGraphConfiguration,
    val cdpApiKey: String,
    val sdkConfig: ConfigDSL<CustomerIOBuilder>,
    val analytics: AnalyticsDSL<Analytics>
) : TestConfig {
    override fun plus(other: TestConfig): DataPipelinesTestConfig {
        val args = arguments + other.arguments
        val diGraphConfig = diGraph + other.diGraph
        if (other !is DataPipelinesTestConfig) {
            return DataPipelinesTestConfig(
                arguments = args,
                diGraph = diGraphConfig,
                cdpApiKey = cdpApiKey,
                sdkConfig = sdkConfig,
                analytics = analytics
            )
        }

        return DataPipelinesTestConfig(
            arguments = args,
            diGraph = diGraphConfig,
            cdpApiKey = other.cdpApiKey,
            sdkConfig = sdkConfig + other.sdkConfig,
            analytics = analytics + other.analytics
        )
    }

    class Builder : TestConfigBuilder<DataPipelinesTestConfig>() {
        private var cdpApiKey: String = TestConstants.Keys.CDP_API_KEY
        private var sdkConfig: ConfigDSL<CustomerIOBuilder> = {}
        private var analytics: AnalyticsDSL<Analytics> = { this }

        fun cdpApiKey(cdpApiKey: String) {
            this.cdpApiKey = cdpApiKey
        }

        fun sdkConfig(block: ConfigDSL<CustomerIOBuilder>) {
            sdkConfig = block
        }

        fun analytics(block: AnalyticsDSL<Analytics>) {
            analytics = block
        }

        override fun build(): DataPipelinesTestConfig = DataPipelinesTestConfig(
            arguments = configArguments,
            diGraph = diGraphConfiguration,
            cdpApiKey = cdpApiKey,
            sdkConfig = sdkConfig,
            analytics = analytics
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
