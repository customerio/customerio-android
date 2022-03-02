package io.customer.sdk.api

import io.customer.sdk.data.request.MetricEvent

/**
 * Apis exposed to clients
 */
interface CustomerIOApi {
    fun identify(identifier: String, attributes: Map<String, Any>)
    fun track(name: String, attributes: Map<String, Any>)
    fun clearIdentify()
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String)
    fun screen(name: String, attributes: Map<String, Any>)
}
