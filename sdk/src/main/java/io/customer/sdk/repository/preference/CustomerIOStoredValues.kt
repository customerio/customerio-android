package io.customer.sdk.repository.preference

import io.customer.sdk.AnalyticsConstants
import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.SDKConstants
import io.customer.sdk.data.model.Region
import io.customer.sdk.util.CioLogLevel

data class CustomerIOStoredValues(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val trackingApiUrl: String? = null,
    val autoTrackDeviceAttributes: Boolean = AnalyticsConstants.AUTO_TRACK_DEVICE_ATTRIBUTES,
    val logLevel: CioLogLevel = SDKConstants.LOG_LEVEL_DEFAULT,
    val backgroundQueueMinNumberOfTasks: Int = AnalyticsConstants.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS,
    val backgroundQueueSecondsDelay: Double = AnalyticsConstants.BACKGROUND_QUEUE_SECONDS_DELAY
) {
    constructor(customerIOConfig: CustomerIOConfig) : this(
        siteId = customerIOConfig.siteId,
        apiKey = customerIOConfig.apiKey,
        region = customerIOConfig.region,
        trackingApiUrl = customerIOConfig.trackingApiUrl,
        autoTrackDeviceAttributes = customerIOConfig.autoTrackDeviceAttributes,
        logLevel = customerIOConfig.logLevel,
        backgroundQueueMinNumberOfTasks = customerIOConfig.backgroundQueueMinNumberOfTasks,
        backgroundQueueSecondsDelay = customerIOConfig.backgroundQueueSecondsDelay
    )

    companion object {
        val empty = CustomerIOStoredValues(
            siteId = "",
            apiKey = "",
            region = Region.US
        )
    }
}

fun CustomerIOStoredValues.doesExist(): Boolean = this.siteId.isNotEmpty()
