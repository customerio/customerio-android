package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.HTTPClient
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.trackEvents
import io.customer.sdk.data.model.CustomAttributes
import io.mockk.every
import io.mockk.mockkConstructor
import okio.IOException
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.amshove.kluent.shouldNotBeNull
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

    @Test
    fun givenComplexEventWithNestedNulls_expectSuccessfulEventProcessingThroughDestination() {
        // Create an event with complex nested structure containing nulls
        val eventName = "complex_event"
        val properties: CustomAttributes = mapOf(
            "customer" to mapOf(
                "id" to 123,
                "name" to "Test Customer",
                "account" to mapOf(
                    "type" to "premium",
                    "paymentMethod" to null, // Null at deeper level
                    "details" to mapOf(
                        "active" to true,
                        "lastPaymentDate" to null // Another null at even deeper level
                    )
                ),
                "preferences" to null // Null at second level
            ),
            "products" to listOf(
                mapOf("id" to 1, "name" to "Product A", "variants" to null), // Null in list item
                mapOf(
                    "id" to 2,
                    "name" to "Product B",
                    "variants" to listOf(
                        mapOf("color" to "red", "size" to null), // Null in nested list item
                        null // Null list item in nested list
                    )
                ),
                null // Null list item
            )
        )

        // Track event with complex properties containing nulls
        sdkInstance.track(eventName, properties)

        // Verify the event was processed successfully
        outputReaderPlugin.trackEvents.size shouldBeEqualTo 1
        val event = outputReaderPlugin.trackEvents.first()
        event.shouldNotBeNull()
        event.event shouldBeEqualTo eventName

        // Verify the nested structures were preserved
        val customer = event.properties["customer"] as? Map<*, *>
        customer.shouldNotBeNull()

        val account = customer["account"] as? Map<*, *>
        account.shouldNotBeNull()

        val details = account["details"] as? Map<*, *>
        details.shouldNotBeNull()

        // Verify the list was properly processed
        val products = event.properties["products"] as? List<*>
        products.shouldNotBeNull()
        products.size shouldBeEqualTo 3

        // Verify an item with a nested list was processed correctly
        val productB = products[1] as? Map<*, *>
        productB.shouldNotBeNull()

        val variants = productB["variants"] as? List<*>
        variants.shouldNotBeNull()
        variants.size shouldBeEqualTo 2
    }
}
