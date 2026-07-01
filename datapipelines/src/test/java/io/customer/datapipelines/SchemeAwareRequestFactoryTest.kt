package io.customer.datapipelines

import com.segment.analytics.kotlin.core.Configuration
import io.customer.commontest.core.RobolectricTest
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.config.ScreenView
import io.customer.datapipelines.extensions.updateAnalyticsConfig
import io.customer.datapipelines.plugins.SchemeAwareRequestFactory
import io.customer.sdk.data.model.Region
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemeAwareRequestFactoryTest : RobolectricTest() {

    // Proves the factory is actually wired into the Configuration that Segment reads
    // (SettingsKt/EventPipeline build their HTTPClient from configuration.requestFactory).
    @Test
    fun updateAnalyticsConfig_installsSchemeAwareRequestFactory() {
        val moduleConfig = DataPipelinesModuleConfig(
            cdpApiKey = "key",
            region = Region.US,
            flushAt = 20,
            flushInterval = 30,
            flushPolicies = emptyList(),
            autoAddCustomerIODestination = true,
            trackApplicationLifecycleEvents = true,
            autoTrackDeviceAttributes = true,
            autoTrackActivityScreens = true,
            screenViewUse = ScreenView.All
        )
        val configuration = Configuration(writeKey = "key").apply {
            updateAnalyticsConfig(moduleConfig = moduleConfig).invoke(this)
        }
        configuration.requestFactory shouldBeInstanceOf SchemeAwareRequestFactory::class
    }

    // openConnection(String) is the choke point for both upload() and settings(); reading
    // conn.url is lazy so this does not hit the network.
    @Test
    fun openConnection_collapsesDoubledSchemeToConfiguredScheme() {
        val factory = SchemeAwareRequestFactory()

        // host configured with explicit http:// -> Segment's https:// must be dropped
        factory.openConnection("https://http://localhost:8080/v1/batch").url.run {
            protocol shouldBeEqualTo "http"
            host shouldBeEqualTo "localhost"
            port shouldBeEqualTo 8080
        }

        // explicit https:// stays https
        factory.openConnection("https://https://example.com/v1").url.protocol shouldBeEqualTo "https"

        // scheme-less host keeps Segment's forced https (no change)
        factory.openConnection("https://cdp.customer.io/v1/batch").url.run {
            protocol shouldBeEqualTo "https"
            host shouldBeEqualTo "cdp.customer.io"
        }
    }
}
