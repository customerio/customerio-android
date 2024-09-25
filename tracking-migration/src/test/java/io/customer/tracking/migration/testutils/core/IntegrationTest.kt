package io.customer.tracking.migration.testutils.core

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.core.RobolectricTest
import io.customer.sdk.core.di.SDKComponent
import io.customer.tracking.migration.testutils.extensions.configureMigrationSDKComponent
import io.customer.tracking.migration.testutils.extensions.migrationSDKComponent

abstract class IntegrationTest : RobolectricTest() {
    private val defaultTestConfiguration: TrackingMigrationTestConfig = testConfiguration {
        argument(ApplicationArgument(applicationMock))
    }

    override fun setup(testConfig: TestConfig) {
        val config = defaultTestConfiguration + testConfig
        super.setup(config)

        SDKComponent.configureMigrationSDKComponent(config)
    }

    override fun teardown() {
        SDKComponent.migrationSDKComponent.reset()

        super.teardown()
    }
}
