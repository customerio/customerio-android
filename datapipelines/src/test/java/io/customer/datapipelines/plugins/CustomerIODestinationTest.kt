package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.HTTPClient
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.mockk.every
import io.mockk.mockkConstructor
import okio.IOException
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Test for CustomerIODestination plugin, focusing on behavior when network settings fetch fails.
 * These tests verify that even when the settings fetch fails, the plugin remains enabled
 * and can process events.
 */
class CustomerIODestinationTest : JUnitTest() {

    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        // Mock HTTP client to throw exception when fetching settings
        mockkConstructor(HTTPClient::class)
        every { anyConstructed<HTTPClient>().settings(any()) } throws IOException("Network error")

        super.setup(
            testConfiguration {
                sdkConfig {
                    autoAddCustomerIODestination(true)
                }
            }
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    // process_givenScreenViewUseAnalytics_expectScreenEventWithoutPropertiesProcessed
    @Test
    fun givenFetchingSettingFails_expectCustomerIODestinationPluginStillBeEnabled() {
        // Verify plugin is present in analytics instance
        sdkInstance.analytics.find(CustomerIODestination::class) shouldNotBe null

        // Track an event to verify it flows through the pipeline
        sdkInstance.track("test_event")

        // Verify event was processed
        outputReaderPlugin.trackEvents.size shouldBe 1
        outputReaderPlugin.trackEvents.first().event shouldBe "test_event"
    }

    @Test
    fun givenDefaultSetting_expectConfigurationIntegrationIncludeCustomerIODestination() {
        // Verify settings contain CustomerIO destination even with network failure
        val configuration = analytics.configuration
        configuration.defaultSettings shouldNotBe null
        val integrations = configuration.defaultSettings?.integrations
        integrations shouldNotBe null
        (integrations?.contains(CUSTOMER_IO_DATA_PIPELINES) ?: false) shouldBe true
    }
}
