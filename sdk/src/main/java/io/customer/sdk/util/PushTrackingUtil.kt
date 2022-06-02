package io.customer.sdk.util

import android.os.Bundle
import io.customer.sdk.CustomerIOInstance
import io.customer.sdk.data.request.MetricEvent

interface PushTrackingUtil {
    fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean
}

class PushTrackingUtilImpl(
    private val customerIOInstance: CustomerIOInstance
) : PushTrackingUtil {

    companion object {
        const val DELIVERY_ID_KEY = "CIO-Delivery-ID"
        const val DELIVERY_TOKEN_KEY = "CIO-Delivery-Token"
    }

    override fun parseLaunchedActivityForTracking(bundle: Bundle): Boolean {
        val deliveryId = bundle.getString(DELIVERY_ID_KEY)
        val deliveryToken = bundle.getString(DELIVERY_TOKEN_KEY)

        if (deliveryId == null || deliveryToken == null) return false

        customerIOInstance.trackMetric(
            deliveryID = deliveryId,
            deviceToken = deliveryToken,
            event = MetricEvent.opened
        )

        return true
    }
}
