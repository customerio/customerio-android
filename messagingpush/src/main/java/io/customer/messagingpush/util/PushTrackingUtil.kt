package io.customer.messagingpush.util

import android.os.Bundle
import io.customer.sdk.data.request.MetricEvent
import io.customer.sdk.repository.TrackRepository

interface PushTrackingUtil {
    fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean

    companion object {
        const val DELIVERY_ID_KEY = "CIO-Delivery-ID"
        const val DELIVERY_TOKEN_KEY = "CIO-Delivery-Token"
    }
}

class PushTrackingUtilImpl(
    private val trackRepository: TrackRepository
) : PushTrackingUtil {

    override fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean {
        val deliveryId = bundle.getString(PushTrackingUtil.DELIVERY_ID_KEY)
        val deliveryToken = bundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY)

        if (deliveryId == null || deliveryToken == null) return false

        trackRepository.trackMetric(
            deliveryID = deliveryId,
            deviceToken = deliveryToken,
            event = MetricEvent.opened
        )

        return true
    }
}
