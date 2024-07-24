package io.customer.datapipelines.extensions

import io.customer.commontest.extensions.random
import io.customer.sdk.events.Metric
import io.customer.sdk.events.TrackMetric
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test

class TrackMetricExtensionsTest {
    @Test
    fun validate_givenAnyMetric_expectCorrectSerialization() {
        for (metric in Metric.values()) {
            val givenTrackEvent = TrackMetric.Push(
                metric = metric,
                deliveryId = String.random,
                deviceToken = String.random
            )

            val result = givenTrackEvent.asMap()

            val expected = when (metric) {
                Metric.Delivered -> "delivered"
                Metric.Opened -> "opened"
                Metric.Converted -> "converted"
                Metric.Clicked -> "clicked"
            }
            result["metric"] shouldBeEqualTo expected
        }
    }

    @Test
    fun asMap_givenPushMetric_expectMapHasCorrectPairs() {
        val givenDeliveryId = String.random
        val givenDeviceToken = String.random
        val givenTrackEvent = TrackMetric.Push(
            metric = Metric.Delivered,
            deliveryId = givenDeliveryId,
            deviceToken = givenDeviceToken
        )

        val result = givenTrackEvent.asMap()

        result["metric"] shouldBeEqualTo "delivered"
        result["deliveryId"] shouldBeEqualTo givenDeliveryId
        result["recipient"] shouldBeEqualTo givenDeviceToken
    }

    @Test
    fun asMap_givenInAppMetric_expectMapHasCorrectPairs() {
        val givenDeliveryId = String.random
        val givenMetadata = mapOf(
            "type" to "persistent",
            "expiry" to "never"
        )
        val givenTrackEvent = TrackMetric.InApp(
            metric = Metric.Clicked,
            deliveryId = givenDeliveryId,
            metadata = givenMetadata
        )

        val result = givenTrackEvent.asMap()

        result["metric"] shouldBeEqualTo "clicked"
        result["deliveryId"] shouldBeEqualTo givenDeliveryId
        result["type"] shouldBeEqualTo "persistent"
        result["expiry"] shouldBeEqualTo "never"
    }
}
