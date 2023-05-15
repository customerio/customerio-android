package io.customer.messagingpush.util

import android.os.Bundle
import io.customer.base.internal.InternalCustomerIOApi
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.TrackRepository

interface PushTrackingUtil {
    @InternalCustomerIOApi
    fun parseAndTrackMetricEvent(bundle: Bundle, event: MetricEvent): Boolean

    @Deprecated(
        "This method is deprecated and will be removed in future releases. Use parseAndTrackMetricEvent instead",
        ReplaceWith("parseAndTrackMetricEvent(bundle = bundle, event = MetricEvent.opened)")
    )
    fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean

    companion object {
        const val DELIVERY_ID_KEY = "CIO-Delivery-ID"
        const val DELIVERY_TOKEN_KEY = "CIO-Delivery-Token"
    }
}

class PushTrackingUtilImpl(
    private val trackRepository: TrackRepository
) : PushTrackingUtil {
    override fun parseAndTrackMetricEvent(bundle: Bundle, event: MetricEvent): Boolean {
        val deliveryId = bundle.getString(PushTrackingUtil.DELIVERY_ID_KEY)
        val deliveryToken = bundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY)

        if (deliveryId == null || deliveryToken == null) return false

        trackRepository.trackMetric(
            deliveryID = deliveryId,
            deviceToken = deliveryToken,
            event = event
        )
        return true
    }

    @Deprecated(
        "This method is deprecated and will be removed in future releases. Use parseAndTrackMetricEvent instead",
        replaceWith = ReplaceWith("parseAndTrackMetricEvent(bundle = bundle, event = MetricEvent.opened)")
    )
    override fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean {
        return parseAndTrackMetricEvent(bundle, MetricEvent.opened)
    }
}
