package io.customer.sdk.extensions

import io.customer.core.util.CioLogLevel
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.request.MetricEvent
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class CustomerIOEExtensionsTest {

    @Test
    fun test_region_givenString_expectRegion() {
        val givenUSRegion = Region.US
        val expectedUSRegion = Region.getRegion(region = "us")

        givenUSRegion shouldBeEqualTo expectedUSRegion

        val givenEURegion = Region.EU
        val expectedEURegion = Region.getRegion(region = "EU")

        givenEURegion shouldBeEqualTo expectedEURegion
    }

    @Test
    fun test_logLevel_givenString_expectLogLevel() {
        val givenLogLevelNone = CioLogLevel.NONE
        val expectedLogLevelNone = CioLogLevel.getLogLevel(level = "none")

        givenLogLevelNone shouldBeEqualTo expectedLogLevelNone

        val givenLogLevelError = CioLogLevel.ERROR
        val expectedLogLevelError = CioLogLevel.getLogLevel(level = "error")

        givenLogLevelError shouldBeEqualTo expectedLogLevelError
    }

    @Test
    fun test_metric_givenString_expectMetric() {
        val givenDeliveredMetric = MetricEvent.delivered
        val expectedDeliveredMetric = MetricEvent.getEvent(event = "delivered")

        givenDeliveredMetric shouldBeEqualTo expectedDeliveredMetric

        val givenOpenedMetric = MetricEvent.opened
        val expectedOpenedMetric = MetricEvent.getEvent(event = "OPENED")

        givenOpenedMetric shouldBeEqualTo expectedOpenedMetric
    }
}
