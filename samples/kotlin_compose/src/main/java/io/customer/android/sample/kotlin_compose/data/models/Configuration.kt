package io.customer.android.sample.kotlin_compose.data.models

import io.customer.android.sample.kotlin_compose.util.CustomerIOSDKConstants
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.core.util.CioLogLevel

data class Configuration(
    var cdpApiKey: String,
    var siteId: String,
    var apiHost: String? = null,
    var cdnHost: String? = null,
    var flushInterval: Int = CustomerIOSDKConstants.FLUSH_INTERVAL,
    var flushAt: Int = CustomerIOSDKConstants.FLUSH_AT,
    var trackScreen: Boolean = CustomerIOSDKConstants.AUTO_TRACK_SCREEN_VIEWS,
    var trackDeviceAttributes: Boolean = CustomerIOSDKConstants.AUTO_TRACK_DEVICE_ATTRIBUTES,
    var debugMode: Boolean = false
)

fun Configuration.setValuesFromBuilder(builder: CustomerIOBuilder): CustomerIOBuilder {
    builder.migrationSiteId(this.siteId)
    this.apiHost?.let { builder.apiHost(it) }
    this.cdnHost?.let { builder.cdnHost(it) }
    builder.flushInterval(this.flushInterval)
    builder.flushAt(this.flushAt)
    builder.autoTrackActivityScreens(this.trackScreen)
    builder.autoTrackDeviceAttributes(this.trackDeviceAttributes)
    builder.logLevel(if (this.debugMode) CioLogLevel.DEBUG else CioLogLevel.ERROR)
    return builder
}
