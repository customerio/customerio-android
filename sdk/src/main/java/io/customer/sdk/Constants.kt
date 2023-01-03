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

object SharedWrapperKeys {

    object Environment {
        const val SITE_ID = "siteId"
        const val API_KEY = "apiKey"
        const val REGION = "region"
        const val ORGANIZATION_ID = "organizationId"
    }

    object Config {
        const val TRACKING_API_URL = "trackingApiUrl"
        const val AUTO_TRACK_PUSH_EVENTS = "autoTrackPushEvents"
        const val AUTO_TRACK_DEVICE_ATTRIBUTES = "autoTrackDeviceAttributes"
        const val LOG_LEVEL = "logLevel"
        const val BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS = "backgroundQueueMinNumberOfTasks"
        const val BACKGROUND_QUEUE_SECONDS_DELAY = "backgroundQueueSecondsDelay"
    }

    object PackageConfig {
        const val SOURCE_SDK_VERSION = "version"
    }
}
