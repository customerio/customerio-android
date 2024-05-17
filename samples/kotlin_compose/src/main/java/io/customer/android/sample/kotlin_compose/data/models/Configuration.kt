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
    builder.setMigrationSiteId(this.siteId)
    this.apiHost?.let { builder.setApiHost(it) }
    this.cdnHost?.let { builder.setCdnHost(it) }
    builder.setFlushInterval(this.flushInterval)
    builder.setFlushAt(this.flushAt)
    builder.setAutoTrackActivityScreens(this.trackScreen)
    builder.setAutoTrackDeviceAttributes(this.trackDeviceAttributes)
    builder.setLogLevel(if (this.debugMode) CioLogLevel.DEBUG else CioLogLevel.ERROR)
    return builder
}
