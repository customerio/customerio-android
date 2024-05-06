package io.customer.android.sample.kotlin_compose.data.models

import io.customer.sdk.CustomerIO
import io.customer.sdk.CustomerIOBuilder
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.AUTO_TRACK_DEVICE_ATTRIBUTES
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_SECONDS_DELAY
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.SHOULD_AUTO_RECORD_SCREEN_VIEWS
import io.customer.sdk.core.util.CioLogLevel

data class Configuration(
    var cdpApiKey: String,
    var siteId: String,
    var apiKey: String,
    var trackUrl: String? = null,
    var backgroundQueueSecondsDelay: Double = BACKGROUND_QUEUE_SECONDS_DELAY,
    var backgroundQueueMinNumTasks: Int = BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS,
    var trackScreen: Boolean = SHOULD_AUTO_RECORD_SCREEN_VIEWS,
    var trackDeviceAttributes: Boolean = AUTO_TRACK_DEVICE_ATTRIBUTES,
    var debugMode: Boolean = false
)

fun Configuration.setValuesFromBuilder(builder: CustomerIO.Builder): CustomerIO.Builder {
    this.trackUrl?.let {
        builder.setTrackingApiURL(trackingApiUrl = it)
    }
    builder.setBackgroundQueueSecondsDelay(this.backgroundQueueSecondsDelay)
    builder.setBackgroundQueueMinNumberOfTasks(this.backgroundQueueMinNumTasks)
    if (this.debugMode) {
        builder.setLogLevel(CioLogLevel.DEBUG)
    } else {
        builder.setLogLevel(CioLogLevel.ERROR)
    }
    builder.autoTrackDeviceAttributes(this.trackDeviceAttributes)
    builder.autoTrackScreenViews(this.trackScreen)
    return builder
}

fun Configuration.setValuesFromBuilder(builder: CustomerIOBuilder): CustomerIOBuilder {
    if (this.debugMode) {
        builder.setLogLevel(CioLogLevel.DEBUG)
    } else {
        builder.setLogLevel(CioLogLevel.ERROR)
    }
    return builder
}
