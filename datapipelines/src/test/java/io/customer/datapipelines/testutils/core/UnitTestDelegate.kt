package io.customer.datapipelines.testutils.core

import android.app.Application
import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.util.DeviceStoreStub
import io.customer.commontest.util.UnitTestLogger
import io.customer.datapipelines.testutils.extensions.registerAnalyticsFactory
import io.customer.datapipelines.testutils.utils.clearPersistentStorage
import io.customer.datapipelines.testutils.utils.createTestAnalyticsInstance
import io.customer.datapipelines.testutils.utils.mockHTTPClient
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import io.customer.sdk.data.store.DeviceStore
import io.customer.sdk.data.store.GlobalPreferenceStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk

/**
 * Base class for unit tests in the data pipelines module.
 * This class provides a setup for the CustomerIO instance and analytics instance.
 * The class is abstract and requires the test application instance to be provided
 * by child classes which allows running tests with or without depending on Android
 * context and resources.
 */
abstract class UnitTestDelegate {
    // Keep application instance as Any to allow using different types of
    // application instances for tests
    abstract var testApplication: Any

    protected lateinit var sdkInstance: CustomerIO
    protected lateinit var analytics: Analytics

    protected lateinit var globalPreferenceStore: GlobalPreferenceStore
    protected lateinit var deviceStore: DeviceStore

    init {
        // Mock HTTP client to override CDP settings with test values
        mockHTTPClient()
    }

    /**
     * Sets up test environment based on the provided [DataPipelinesTestConfig]
     * This function initializes necessary components and configurations required for running local tests
     *
     * @param testConfig Configuration object containing all setups for the test environment
     */
    protected open fun setupTestEnvironment(testConfig: DataPipelinesTestConfig = testConfiguration { }) {
        setupSDKComponent(testConfig = testConfig)

        sdkInstance = initializeSDKForTesting(testConfig = testConfig)
        analytics = sdkInstance.analytics
    }

    protected open fun setupSDKComponent(testConfig: DataPipelinesTestConfig) {
        // Setup analytics factory to create test analytics instance
        SDKComponent.registerAnalyticsFactory { moduleConfig ->
            val testAnalyticsInstance = createTestAnalyticsInstance(moduleConfig, application = testApplication)
            // Configure plugins for the test analytics instance to allow adding
            // desired plugins before CustomerIO initialization
            testConfig.configurePlugins(testAnalyticsInstance)
            return@registerAnalyticsFactory testAnalyticsInstance
        }
        // Override logger dependency with test logger so logs can be captured in tests
        // This also makes logger independent of Android Logcat
        SDKComponent.overrideDependency(Logger::class.java, UnitTestLogger())
    }

    protected open fun setupAndroidSDKComponent() {
        // Setup AndroidSDKComponent by mocking dependencies that depends on Android context
        // Prefer relaxed mocks to avoid unnecessary setup for methods that are not used in the test
        val androidSDKComponent = SDKComponent.android()
        // Mock global preference store to avoid reading/writing to shared preferences
        globalPreferenceStore = mockk<GlobalPreferenceStore>(relaxUnitFun = true).also { instance ->
            androidSDKComponent.overrideDependency(GlobalPreferenceStore::class.java, instance)
        }
        every { globalPreferenceStore.getDeviceToken() } returns null
        // Mock device store to avoid reading/writing to device store
        // Spy on the stub to provide custom implementation for the test
        val deviceStoreStub = DeviceStoreStub().getDeviceStore(androidSDKComponent.client)
        deviceStore = spyk(deviceStoreStub).also { instance ->
            androidSDKComponent.overrideDependency(DeviceStore::class.java, instance)
        }
    }

    protected open fun initializeSDKForTesting(testConfig: DataPipelinesTestConfig): CustomerIO {
        // Cast application mock to reuse mocked application context if provided
        // Else create mocked application context to avoid using real context in tests
        val applicationContext = testApplication as? Application ?: mockk(relaxed = true)
        val builder = CustomerIOBuilder(applicationContext, testConfig.cdpApiKey)
        // Register AndroidSDKComponent with mocked dependencies as we are not using real context in tests
        setupAndroidSDKComponent()
        // Disable adding destination to analytics instance so events are not sent to the server by default
        builder.setAutoAddCustomerIODestination(false)
        // Disable tracking application lifecycle events by default to avoid tracking unnecessary events while testing
        builder.setTrackApplicationLifecycleEvents(false)
        // Apply custom configuration for the test at the end to allow overriding default configurations
        testConfig.sdkConfig(builder)
        return builder.build()
    }

    protected open fun deinitializeModule() {
        analytics.clearPersistentStorage()
        CustomerIO.clearInstance()
    }
}
