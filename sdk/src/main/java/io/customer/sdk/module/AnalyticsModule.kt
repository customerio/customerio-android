package io.customer.sdk.module

import android.app.Activity
import io.customer.sdk.data.model.CustomAttributes
import io.customer.sdk.data.request.MetricEvent

interface AnalyticsModule<Config : CustomerIOModuleConfig> : CustomerIOModule<Config> {
    suspend fun cleanup()
    var profileAttributes: CustomAttributes
    var deviceAttributes: CustomAttributes

    val registeredDeviceToken: String?

    fun identify(identifier: String)

    fun identify(
        identifier: String,
        attributes: Map<String, Any>
    )

    fun track(name: String)

    fun track(
        name: String,
        attributes: Map<String, Any>
    )

    fun screen(name: String)

    fun screen(
        name: String,
        attributes: Map<String, Any>
    )

    fun screen(activity: Activity)

    fun screen(
        activity: Activity,
        attributes: Map<String, Any>
    )

    fun clearIdentify()

    fun registerDeviceToken(deviceToken: String, deviceAttributes: CustomAttributes)

    fun deleteDeviceToken()

    fun trackMetric(
        deliveryID: String,
        event: MetricEvent,
        deviceToken: String
    )

    fun addCustomDeviceAttributes(deviceAttributes: CustomAttributes)
    fun addCustomProfileAttributes(deviceAttributes: CustomAttributes)
    fun trackInAppMetric(
        deliveryID: String,
        event: MetricEvent,
        metadata: Map<String, String> = emptyMap()
    )
}
