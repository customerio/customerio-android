package io.customer.datapipelines.plugins

import io.customer.commontest.config.TestConfig
import io.customer.commontest.extensions.random
import io.customer.datapipelines.config.ScreenView
import io.customer.datapipelines.testutils.core.DataPipelinesTestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.datapipelines.testutils.core.testConfiguration
import io.customer.datapipelines.testutils.extensions.shouldMatchTo
import io.customer.datapipelines.testutils.utils.OutputReaderPlugin
import io.customer.datapipelines.testutils.utils.screenEvents
import io.customer.sdk.data.model.CustomAttributes
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSingleItem
import org.junit.jupiter.api.Test

class ScreenFilterPluginTest : JUnitTest() {
    private lateinit var outputReaderPlugin: OutputReaderPlugin

    override fun setup(testConfig: TestConfig) {
        // Keep setup empty to avoid calling super.setup() as it will initialize the SDK
        // and we want to test the SDK with different configurations in each test
    }

    private fun setupWithConfig(screenViewUse: ScreenView, testConfig: DataPipelinesTestConfig = testConfiguration {}) {
        super.setup(
            testConfiguration {
                analytics { add(ScreenFilterPlugin(screenViewUse = screenViewUse)) }
            } + testConfig
        )

        outputReaderPlugin = OutputReaderPlugin()
        analytics.add(outputReaderPlugin)
    }

    @Test
    fun process_givenScreenViewUseAnalytics_expectScreenEventWithoutPropertiesProcessed() {
        setupWithConfig(screenViewUse = ScreenView.All)

        val givenScreenTitle = String.random
        sdkInstance.screen(givenScreenTitle)

        val result = outputReaderPlugin.screenEvents.shouldHaveSingleItem()
        result.name shouldBeEqualTo givenScreenTitle
        result.properties.shouldBeEmpty()
    }

    @Test
    fun process_givenScreenViewUseAnalytics_expectScreenEventWithPropertiesProcessed() {
        setupWithConfig(screenViewUse = ScreenView.All)

        val givenScreenTitle = String.random
        val givenProperties: CustomAttributes = mapOf("source" to "push", "discount" to 10)
        sdkInstance.screen(givenScreenTitle, givenProperties)

        val screenEvent = outputReaderPlugin.screenEvents.shouldHaveSingleItem()
        screenEvent.name shouldBeEqualTo givenScreenTitle
        screenEvent.properties shouldMatchTo givenProperties
    }

    @Test
    fun process_givenScreenViewUseInApp_expectAllScreenEventsIgnored() {
        setupWithConfig(screenViewUse = ScreenView.InApp)

        for (i in 1..5) {
            sdkInstance.screen(String.random)
        }

        outputReaderPlugin.allEvents.shouldBeEmpty()
    }
}
