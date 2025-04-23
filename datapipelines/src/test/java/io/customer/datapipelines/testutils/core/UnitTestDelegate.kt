package io.customer.datapipelines.testutils.core

import android.app.Application
import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.config.configureAndroidSDKComponent
import io.customer.commontest.util.DeviceStoreStub
import io.customer.datapipelines.testutils.extensions.registerAnalyticsFactory
import io.customer.datapipelines.testutils.stubs.TestCoroutineConfiguration
import io.customer.datapipelines.testutils.utils.clearPersistentStorage
import io.customer.datapipelines.testutils.utils.createTestAnalyticsInstance
import io.customer.datapipelines.testutils.utils.mockHTTPClient
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Delegate class for setting up test environment for running unit tests.
 * The class makes it easy to setup both JUnit and Robolectric tests by delegating setup to this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnitTestDelegate(
    val applicationInstance: Any,
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) {
    val testCoroutineConfiguration = TestCoroutineConfiguration(testDispatcher)
    val testScope get() = testCoroutineConfiguration.testScope

    private lateinit var testConfig: DataPipelinesTestConfig
    lateinit var sdkInstance: CustomerIO

    // analytics instance that can be used to spy on
    lateinit var analytics: Analytics

    init {
        // Mock HTTP client to override CDP settings with test values
        mockHTTPClient()
    }

    /**
     * Sets up test environment based on the provided [DataPipelinesTestConfig]
     * This function initializes necessary components and configurations required for running local tests
     *
     * @param config Configuration object containing all setups for the test environment
     */
    fun setup(config: DataPipelinesTestConfig) {
        testConfig = config
        mockAnalytics()
        sdkInstance = initializeSDKComponent()
        analytics = sdkInstance.analytics
    }

    private fun mockAnalytics() {
        // Setup analytics factory to create test analytics instance
        SDKComponent.registerAnalyticsFactory { moduleConfig ->
            val testAnalyticsInstance = createTestAnalyticsInstance(
                moduleConfig = moduleConfig,
                application = applicationInstance,
                testDispatcher = testDispatcher,
                testCoroutineConfiguration = testCoroutineConfiguration
            )
            // Configure plugins for the test analytics instance to allow adding
            // desired plugins before CustomerIO initialization
            testConfig.analytics(testAnalyticsInstance)
            return@registerAnalyticsFactory spyk(testAnalyticsInstance)
        }
    }

    private fun overrideAndroidComponentDependencies() {
        // Setup AndroidSDKComponent by mocking dependencies that depends on Android context
        // Prefer relaxed mocks to avoid unnecessary setup for methods that are not used in the test
        val androidSDKComponent = SDKComponent.android()
        // Mock global preference store to avoid reading/writing to shared preferences
        val globalPreferenceStore = mockk<GlobalPreferenceStore>(relaxUnitFun = true).also { instance ->
            androidSDKComponent.overrideDependency<GlobalPreferenceStore>(instance)
        }
        every { globalPreferenceStore.getDeviceToken() } returns null
        // Mock device store to avoid reading/writing to device store
        // Spy on the stub to provide custom implementation for the test
        val deviceStoreStub = DeviceStoreStub().getDeviceStore(androidSDKComponent.client)
        spyk(deviceStoreStub).also { instance ->
            androidSDKComponent.overrideDependency<DeviceStore>(instance)
        }
        // Apply custom configuration for the test at the end to allow overriding default configurations
        testConfig.configureAndroidSDKComponent(androidSDKComponent)
    }

    private fun initializeSDKComponent(): CustomerIO {
        // Cast application mock to reuse mocked application context if provided
        // Else create mocked application context to avoid using real context in tests
        val applicationContext = applicationInstance as? Application ?: mockk<Application>(relaxed = true).apply {
            every { applicationContext } returns this
        }
        val builder = CustomerIOBuilder(applicationContext, testConfig.cdpApiKey)
        // Register AndroidSDKComponent with mocked dependencies as we are not using real context in tests
        overrideAndroidComponentDependencies()
        // Disable adding destination to analytics instance so events are not sent to the server by default
        builder.autoAddCustomerIODestination(false)
        // Disable tracking application lifecycle events by default to avoid tracking unnecessary events while testing
        builder.trackApplicationLifecycleEvents(false)
        // Apply custom configuration for the test at the end to allow overriding default configurations
        testConfig.sdkConfig(builder)
        return builder.build()
    }

    fun teardownSDKComponent() {
        analytics.clearPersistentStorage()
        CustomerIO.clearInstance()
    }
}
