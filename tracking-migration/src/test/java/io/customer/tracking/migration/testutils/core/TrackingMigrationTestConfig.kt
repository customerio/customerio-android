package io.customer.tracking.migration.testutils.core

import io.customer.commontest.config.ConfigDSL
import io.customer.commontest.config.DIGraphConfiguration
import io.customer.commontest.config.TestArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.TestConfigBuilder
import io.customer.commontest.config.plus
import io.customer.commontest.core.TestConstants
import io.customer.tracking.migration.MigrationProcessor
import io.customer.tracking.migration.di.MigrationSDKComponent
import io.mockk.mockk

/**
 * Test configuration for setting up tracking migration module and its dependencies.
 *
 * @property migrationSDKComponent used to override dependencies in [MigrationSDKComponent] for testing
 * @property migrationSiteId site ID to be used by tracking migration module
 * @property migrationProcessor to process migration tasks, default is a mockk instance with relaxed mode disabled
 */
class TrackingMigrationTestConfig private constructor(
    override val arguments: List<TestArgument>,
    override val diGraph: DIGraphConfiguration,
    val migrationSDKComponent: ConfigDSL<MigrationSDKComponent>,
    val migrationSiteId: String,
    val migrationProcessor: MigrationProcessor
) : TestConfig {
    override fun plus(other: TestConfig): TrackingMigrationTestConfig {
        val args = arguments + other.arguments
        val diGraphConfig = diGraph + other.diGraph
        if (other !is TrackingMigrationTestConfig) {
            return TrackingMigrationTestConfig(
                arguments = args,
                diGraph = diGraphConfig,
                migrationSDKComponent = migrationSDKComponent,
                migrationSiteId = migrationSiteId,
                migrationProcessor = migrationProcessor
            )
        }

        return TrackingMigrationTestConfig(
            arguments = args,
            diGraph = diGraphConfig,
            migrationSDKComponent = migrationSDKComponent + other.migrationSDKComponent,
            migrationSiteId = other.migrationSiteId,
            migrationProcessor = other.migrationProcessor
        )
    }

    class Builder : TestConfigBuilder<TrackingMigrationTestConfig>() {
        private var migrationSDKComponent: ConfigDSL<MigrationSDKComponent> = {}
        private var migrationSiteId: String = TestConstants.Keys.SITE_ID

        // Use relaxed unit mocks instead of full relaxed mocks to minimize false positives
        private var migrationProcessor: MigrationProcessor = mockk()

        // Add extension function on DIGraphConfiguration so caller can setup MigrationSDKComponent
        // just like other DI components
        fun DIGraphConfiguration.migrationSDKComponent(block: ConfigDSL<MigrationSDKComponent>) {
            migrationSDKComponent = block
        }

        fun migrationSiteId(siteId: String) {
            this.migrationSiteId = siteId
        }

        fun migrationProcessor(processor: MigrationProcessor) {
            this.migrationProcessor = processor
        }

        override fun build(): TrackingMigrationTestConfig = TrackingMigrationTestConfig(
            arguments = configArguments,
            diGraph = diGraphConfiguration,
            migrationSDKComponent = migrationSDKComponent,
            migrationSiteId = migrationSiteId,
            migrationProcessor = migrationProcessor
        )
    }
}

/**
 * Creates [TrackingMigrationTestConfig] using DSL-like option for convenient initialization
 *
 * @param block Configuration block for [TrackingMigrationTestConfig.Builder].
 */
fun testConfiguration(block: TrackingMigrationTestConfig.Builder.() -> Unit): TrackingMigrationTestConfig {
    return TrackingMigrationTestConfig.Builder().apply(block).build()
}
