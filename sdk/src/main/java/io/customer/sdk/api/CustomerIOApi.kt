package io.customer.sdk.api

import io.customer.base.comunication.Action
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.MetricEvent

/**
 * Apis exposed to clients
 */
internal interface CustomerIOApi {
    fun identify(identifier: String, attributes: CustomAttributes): Action<Unit>
    fun track(name: String, attributes: CustomAttributes)
    fun clearIdentify()
    fun registerDeviceToken(deviceToken: String): Action<Unit>
    fun deleteDeviceToken(): Action<Unit>
    fun trackMetric(deliveryID: String, event: MetricEvent, deviceToken: String): Action<Unit>
    fun screen(name: String, attributes: CustomAttributes)
}
