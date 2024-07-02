package io.customer.commontest.core

import io.customer.commontest.config.ApplicationArgument
import io.customer.commontest.config.ClientArgument
import io.customer.commontest.config.TestConfig
import io.customer.commontest.config.argumentOrNull
import io.customer.commontest.config.configureAndroidSDKComponent
import io.customer.commontest.config.configureSDKComponent
import io.customer.commontest.config.testConfigurationDefault
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.mockk.clearAllMocks

/**
 * Base class for all tests in the project.
 * This class is responsible for basic setup and teardown of test environment, like
 * initializing the SDK component and clearing all mocks.
 * The class should only contain the common setup and teardown logic for all tests.
 * Any additional setup or teardown logic should be implemented in respective child classes.
 */
abstract class BaseTest {
    protected open fun setup(testConfig: TestConfig = testConfigurationDefault { }) {
        testConfig.configureSDKComponent()
        registerAndroidSDKComponent(testConfig)
    }

    protected open fun teardown() {
        clearAllComponents()
    }

    protected open fun clearAllComponents() {
        SDKComponent.reset()
        clearAllMocks()
    }

    /**
     * For modules that depend on Android SDK components and not calling SDK initialization directly,
     * this is required to initialize AndroidSDKComponent for such modules
     */
    private fun registerAndroidSDKComponent(testConfig: TestConfig) {
        val application = testConfig.argumentOrNull<ApplicationArgument>()?.value ?: return
        val client = testConfig.argumentOrNull<ClientArgument>()?.value ?: return

        testConfig.configureAndroidSDKComponent(SDKComponent.registerAndroidSDKComponent(application, client))
    }
}
