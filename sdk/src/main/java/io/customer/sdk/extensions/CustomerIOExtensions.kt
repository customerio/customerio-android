/* ktlint-disable filename */ // until this extension file contains 2+ functions in it, we will disable this ktlint rule.
package io.customer.sdk.extensions

import io.customer.sdk.CustomerIO
import io.customer.sdk.SharedWrapperKeys

internal fun CustomerIO.Builder.setupConfig(config: Map<String, Any?>?): CustomerIO.Builder {
    if (config == null) return this

    val logLevel = config.getProperty<String>(SharedWrapperKeys.Config.LOG_LEVEL).toCIOLogLevel()
    setLogLevel(level = logLevel)
    config.getProperty<String>(SharedWrapperKeys.Config.TRACKING_API_URL)?.takeIfNotBlank()?.let { value ->
        setTrackingApiURL(value)
    }
    config.getProperty<Boolean>(SharedWrapperKeys.Config.AUTO_TRACK_DEVICE_ATTRIBUTES)?.let { value ->
        autoTrackDeviceAttributes(shouldTrackDeviceAttributes = value)
    }
    config.getProperty<Int>(SharedWrapperKeys.Config.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS)?.let { value ->
        setBackgroundQueueMinNumberOfTasks(backgroundQueueMinNumberOfTasks = value)
    }
    config.getProperty<Double>(SharedWrapperKeys.Config.BACKGROUND_QUEUE_SECONDS_DELAY)?.let { value ->
        setBackgroundQueueSecondsDelay(backgroundQueueSecondsDelay = value)
    }
    return this
}
