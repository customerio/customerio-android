package io.customer.sdk.repository.preference

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.data.model.Region
import io.customer.sdk.util.CioLogLevel

data class CustomerIOStoredValues(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val trackingApiUrl: String? = null,
    val autoTrackDeviceAttributes: Boolean = true,
    val logLevel: CioLogLevel = CioLogLevel.ERROR,
    val backgroundQueueMinNumberOfTasks: Int = 10,
    val backgroundQueueSecondsDelay: Double = 30.0
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
