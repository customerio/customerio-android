package io.customer.datapipelines.support.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.datapipelines.support.extensions.registerAnalyticsFactory
import io.customer.datapipelines.support.utils.clearPersistentStorage
import io.customer.datapipelines.support.utils.createTestAnalyticsInstance
import io.customer.datapipelines.support.utils.mockHTTPClient
import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.Version
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.di.registerAndroidSDKComponent
import io.customer.sdk.data.store.Client
import io.customer.sdk.data.store.GlobalPreferenceStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.mock

/**
 * Base class for unit tests in the data pipelines module.
 * This class provides a setup for the CustomerIO instance and analytics instance
 * along with OutputReaderPlugin to read the events sent to the analytics instance.
 */
abstract class UnitTest {
    protected lateinit var sdkInstance: CustomerIO
    protected lateinit var analytics: Analytics
    protected lateinit var globalPreferenceStore: GlobalPreferenceStore

    protected val cdpApiKey: String
        get() = sdkInstance.moduleConfig.cdpApiKey

    @BeforeEach
    open fun setup() {
        initializeModule()
    }

    protected open fun initializeModule() {
        setupDependencies()

        sdkInstance = createModuleInstance()
        analytics = sdkInstance.analytics
    }

    protected open fun setupDependencies() {
        // Mock HTTP client to override CDP settings with test values
        mockHTTPClient()
        // Setup analytics factory to create test analytics instance
        SDKComponent.registerAnalyticsFactory { moduleConfig ->
            return@registerAnalyticsFactory createTestAnalyticsInstance(moduleConfig)
        }
        // Register Android SDK component with mocked dependencies as we are using real context in tests
        val androidSDKComponent = SDKComponent.registerAndroidSDKComponent(
            context = mock(),
            client = Client.Android(Version.version)
        )
        // Mock global preference store to avoid reading/writing to shared preferences
        globalPreferenceStore = mock()
        androidSDKComponent.overrideDependency(GlobalPreferenceStore::class.java, globalPreferenceStore)
    }

    protected open fun createModuleInstance(
        cdpApiKey: String = TEST_CDP_API_KEY,
        applyConfig: CustomerIOBuilder.() -> Unit = {}
    ): CustomerIO {
        val builder = CustomerIOBuilder(mock(), cdpApiKey)
        // Disable adding destination to analytics instance so events are not sent to the server by default
        builder.setAutoAddCustomerIODestination(false)
        // Disable tracking application lifecycle events by default to avoid tracking unnecessary events while testing
        builder.setTrackApplicationLifecycleEvents(false)
        // Apply custom configuration for the test
        builder.applyConfig()
        return builder.build()
    }

    @AfterEach
    open fun teardown() {
        deinitializeModule()
    }

    protected open fun deinitializeModule() {
        analytics.clearPersistentStorage()
        CustomerIO.clearInstance()
    }

    companion object {
        internal const val TEST_CDP_API_KEY: String = "TESTING_API_KEY"
    }
}
