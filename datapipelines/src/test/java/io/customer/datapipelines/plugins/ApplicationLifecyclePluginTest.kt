package io.customer.datapipelines.plugins

import com.segment.analytics.kotlin.core.Analytics
import com.segment.analytics.kotlin.core.TrackEvent
import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.platform.Plugin
import io.customer.commontest.config.TestConfig
import io.customer.datapipelines.testutils.core.JUnitTest
import io.customer.sdk.util.EventNames
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ApplicationLifecyclePluginTest : JUnitTest() {
    private lateinit var plugin: ApplicationLifecyclePlugin
    private lateinit var mockAnalytics: Analytics

    override fun setup(testConfig: TestConfig) {
        super.setup(testConfig)

        mockAnalytics = mockk(relaxed = true)
        plugin = ApplicationLifecyclePlugin()
        plugin.analytics = mockAnalytics
    }

    @Test
    fun type_verifyPluginTypeIsAfter() {
        plugin.type shouldBeEqualTo Plugin.Type.After
    }

    @Test
    fun track_givenApplicationBackgroundedEvent_expectAnalyticsFlushed() {
        val event = TrackEvent(
            event = EventNames.APPLICATION_BACKGROUNDED,
            properties = emptyJsonObject
        )

        val result = plugin.track(event)

        verify(exactly = 1) { mockAnalytics.flush() }
        result shouldBeEqualTo event
    }

    @ParameterizedTest
    @ValueSource(strings = ["Custom Event", "Application Foregrounded"])
    fun track_givenOtherEvent_expectAnalyticsNotFlushed(eventName: String) {
        val event = TrackEvent(
            event = eventName,
            properties = emptyJsonObject
        )

        val result = plugin.track(event)

        verify(exactly = 0) { mockAnalytics.flush() }
        result shouldBeEqualTo event
    }

    @Test
    fun track_givenMultipleBackgroundEvents_expectFlushCalledEachTime() {
        val event1 = TrackEvent(
            event = EventNames.APPLICATION_BACKGROUNDED,
            properties = emptyJsonObject
        )
        val event2 = TrackEvent(
            event = EventNames.APPLICATION_BACKGROUNDED,
            properties = emptyJsonObject
        )

        plugin.track(event1)
        plugin.track(event2)

        verify(exactly = 2) { mockAnalytics.flush() }
    }
}
