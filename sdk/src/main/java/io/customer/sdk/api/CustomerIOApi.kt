package io.customer.sdk.api

import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.MetricEvent

internal interface CustomerIOApi {
    fun identify(identifier: String, attributes: CustomAttributes)
    fun track(name: String, attributes: CustomAttributes)
    fun clearIdentify()
    fun registerDeviceToken(deviceToken: String, attributes: CustomAttributes)
    fun addCustomDeviceAttributes(attributes: CustomAttributes)
    fun deleteDeviceToken()
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String)
    fun screen(name: String, attributes: CustomAttributes)
}
