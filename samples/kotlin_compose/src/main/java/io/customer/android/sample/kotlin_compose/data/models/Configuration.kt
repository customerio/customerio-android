package io.customer.android.sample.kotlin_compose.data.models

import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.AUTO_TRACK_DEVICE_ATTRIBUTES
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_SECONDS_DELAY
import io.customer.sdk.CustomerIOConfig.Companion.AnalyticsConstants.SHOULD_AUTO_RECORD_SCREEN_VIEWS

data class Configuration(
    var siteId: String,
    var apiKey: String
) {
    var trackUrl: String? = null

    var backgroundQueueSecondsDelay: Double = BACKGROUND_QUEUE_SECONDS_DELAY
    var backgroundQueueMinNumTasks: Int = BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS

    var trackScreen: Boolean = SHOULD_AUTO_RECORD_SCREEN_VIEWS
    var trackDeviceAttributes: Boolean = AUTO_TRACK_DEVICE_ATTRIBUTES
    var debugMode: Boolean = false
}
