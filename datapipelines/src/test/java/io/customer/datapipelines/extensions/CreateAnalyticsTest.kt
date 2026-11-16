package io.customer.datapipelines.extensions

import com.segment.analytics.kotlin.android.Analytics as AndroidAnalytics
import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.Configuration
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.commontest.core.RobolectricTest
import io.customer.datapipelines.config.DataPipelinesModuleConfig
import io.customer.datapipelines.config.ScreenView
import io.customer.datapipelines.testutils.utils.mockHTTPClient
import io.customer.sdk.data.model.Region
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * With no test hook registered, [createAnalytics] builds the same Analytics as Segment's Android
 * builder `Analytics(writeKey, context, configs)`, the call it replaces in [io.customer.sdk.CustomerIO].
 */
@RunWith(RobolectricTestRunner::class)
class CreateAnalyticsTest : RobolectricTest() {

    private val moduleConfig = DataPipelinesModuleConfig(
        cdpApiKey = "create-analytics-test",
        region = Region.US,
        flushAt = 20,
        flushInterval = 30,
        flushPolicies = emptyList(),
        autoAddCustomerIODestination = true,
        trackApplicationLifecycleEvents = true,
        autoTrackDeviceAttributes = true,
        autoTrackActivityScreens = false,
        screenViewUse = ScreenView.All
    )

    private val errorHandler: (Throwable) -> Unit = {}

    private val configs: Configuration.() -> Unit = updateAnalyticsConfig(moduleConfig = moduleConfig, errorHandler = errorHandler)

    /** What the comparison reads off an instance, taken before it is shut down. */
    private data class Built(
        val type: Class<*>,
        val configuration: Configuration,
        val requestFactoryType: Class<*>,
        val dispatcherTypes: List<Class<*>>,
        val pluginTypes: List<Class<*>>
    )

    private fun Analytics.snapshot(): Built = Built(
        type = this::class.java,
        configuration = configuration,
        requestFactoryType = configuration.requestFactory::class.java,
        dispatcherTypes = listOf(analyticsDispatcher, networkIODispatcher, fileIODispatcher).map { it::class.java },
        pluginTypes = findAll(Plugin::class).map { it::class.java }
    )

    // One instance at a time: both use the same write key, so they share Segment's storage
    private fun build(create: () -> Analytics): Built {
        val analytics = create()
        return try {
            analytics.snapshot()
        } finally {
            analytics.shutdown()
        }
    }

    @Test
    fun createAnalytics_givenNoHookRegistered_expectSameAnalyticsAsSegmentBuilder() {
        mockHTTPClient()
        val expected = build { AndroidAnalytics(writeKey = moduleConfig.cdpApiKey, context = applicationMock, configs = configs) }
        val actual = build { createAnalytics(writeKey = moduleConfig.cdpApiKey, context = applicationMock, configs = configs) }

        actual.type shouldBeEqualTo expected.type
        actual.requestFactoryType shouldBeEqualTo expected.requestFactoryType
        // Segment makes a new request factory per configuration, so it is compared by type above
        actual.configuration.copy(requestFactory = expected.configuration.requestFactory) shouldBeEqualTo expected.configuration
        actual.dispatcherTypes shouldBeEqualTo expected.dispatcherTypes
        actual.pluginTypes shouldBeEqualTo expected.pluginTypes
    }
}
