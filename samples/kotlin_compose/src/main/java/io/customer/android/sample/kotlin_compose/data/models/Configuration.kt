package io.customer.android.sample.kotlin_compose.data.models

data class Configuration(
    var siteId: String,
    var apiKey: String
) {
    var trackUrl: String? = null

    var backgroundQueueSecondsDelay: Double = 30.0
    var backgroundQueueMinNumTasks: Int = 10

    var trackScreen: Boolean = false
    var trackDeviceAttributes: Boolean = false
    var debugMode: Boolean = false
}
