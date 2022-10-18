package io.customer.sdk

import io.customer.sdk.util.CioLogLevel

/**
 * SDK constants to avoid repetitive configuration values
 */
object SDKConstants {
    val LOG_LEVEL_DEFAULT = CioLogLevel.ERROR
}

/**
 * Analytics tracking module constants to avoid repetitive configuration values
 */
object AnalyticsConstants {
    const val AUTO_TRACK_DEVICE_ATTRIBUTES = true
    const val BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS = 10
    const val BACKGROUND_QUEUE_SECONDS_DELAY = 30.0
    const val HTTP_REQUEST_TIMEOUT = 6000L
    const val SHOULD_AUTO_RECORD_SCREEN_VIEWS = true
}
