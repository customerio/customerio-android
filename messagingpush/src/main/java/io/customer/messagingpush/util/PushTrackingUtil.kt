package io.customer.messagingpush.util

import android.os.Bundle
import io.customer.sdk.communication.Event
import io.customer.sdk.core.di.SDKComponent.eventBus
import io.customer.sdk.data.request.MetricEvent

interface PushTrackingUtil {
    fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean

    companion object {
        const val DELIVERY_ID_KEY = "CIO-Delivery-ID"
        const val DELIVERY_TOKEN_KEY = "CIO-Delivery-Token"
    }
}

class PushTrackingUtilImpl : PushTrackingUtil {

    override fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean {
        val deliveryId = bundle.getString(PushTrackingUtil.DELIVERY_ID_KEY)
        val deliveryToken = bundle.getString(PushTrackingUtil.DELIVERY_TOKEN_KEY)

        if (deliveryId == null || deliveryToken == null) return false

        eventBus.publish(
            Event.TrackPushMetricEvent(
                deliveryId = deliveryId,
                event = MetricEvent.opened.name,
                deviceToken = deliveryToken
            )
        )

        return true
    }
}
