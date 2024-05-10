package io.customer.datapipelines.core

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.BaseUnitTest
import io.customer.datapipelines.extensions.requireAndroidSDKComponent
import io.customer.datapipelines.utils.OutputReaderPlugin
import io.customer.datapipelines.utils.clearPersistentStorage
import io.customer.datapipelines.utils.createTestAnalyticsInstance
import io.customer.datapipelines.utils.mockHTTPClient
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.android.CustomerIO
import io.customer.sdk.core.di.SDKComponent
import org.mockito.kotlin.mock

/**
 * Base class for unit tests in the data pipelines module.
 * This class provides a setup for the CustomerIO instance and analytics instance
 * along with OutputReaderPlugin to read the events sent to the analytics instance.
 */
abstract class UnitTest : BaseUnitTest() {
    protected lateinit var sdkInstance: CustomerIO
    protected lateinit var analytics: Analytics
    protected lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup() {
        super.setup()

        initializeModule()
    }

    private fun initializeModule() {
        mockHTTPClient()

        sdkInstance = createModuleInstance()
        analytics = sdkInstance.analytics

        outputReaderPlugin = OutputReaderPlugin(analytics)
        analytics.add(outputReaderPlugin)
    }

    protected open fun createModuleInstance(
        cdpApiKey: String = TEST_CDP_API_KEY,
        applyConfig: CustomerIOBuilder.() -> Unit = {}
    ): CustomerIO {
        val builder = CustomerIOBuilder(mock(), cdpApiKey)
        // Disable adding destination to analytics instance so events are not sent to the server by default
        builder.setAutoAddCustomerIODestination(false)
        // Setup data pipeline module to use test analytics instance
        builder.setOverrideDataPipelineInstance { moduleConfig ->
            return@setOverrideDataPipelineInstance CustomerIO(
                androidSDKComponent = SDKComponent.requireAndroidSDKComponent(),
                moduleConfig = moduleConfig,
                overrideAnalytics = createTestAnalyticsInstance(moduleConfig)
            )
        }
        // Apply custom configuration for the test
        builder.applyConfig()
        return builder.build()
    }

    override fun teardown() {
        super.teardown()

        deinitializeModule()
    }

    protected open fun deinitializeModule() {
        analytics.clearPersistentStorage()
        CustomerIO.clearInstance()
    }

    companion object {
        private const val TEST_CDP_API_KEY: String = "TESTING_API_KEY"
    }
}
