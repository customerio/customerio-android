package io.customer.datapipelines.handoff

import com.segment.analytics.kotlin.core.Analytics
import io.customer.commontest.core.JUnit5Test
import io.customer.datapipelines.testutils.extensions.registerSegmentGatewayFactory
import io.customer.sdk.core.di.SDKComponent
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SegmentGatewayTest : JUnit5Test() {

    @Test
    fun gateway_givenNoFactoryRegistered_expectCallsMadeOnAnalyticsUnchanged() {
        val analytics = mockk<Analytics>(relaxed = true)
        val properties = buildJsonObject { put("key", JsonPrimitive("value")) }

        val gateway = analytics.gateway()
        gateway.track("event", properties)
        gateway.screen("screen")
        gateway.reset()

        gateway.shouldBeInstanceOf<AnalyticsSegmentGateway>()
        verify(exactly = 1) { analytics.track("event", properties) }
        verify(exactly = 1) { analytics.screen("screen") }
        verify(exactly = 1) { analytics.reset() }
    }

    @Test
    fun gateway_givenSameAnalytics_expectSameGateway() {
        val analytics = mockk<Analytics>(relaxed = true)

        analytics.gateway() shouldBe analytics.gateway()
    }

    @Test
    fun gateway_givenFactoryRegistered_expectFactoryGateway() {
        val analytics = mockk<Analytics>(relaxed = true)
        val wrapped = mockk<SegmentGateway>(relaxed = true)
        SDKComponent.registerSegmentGatewayFactory { wrapped }

        analytics.gateway() shouldBe wrapped
    }
}
