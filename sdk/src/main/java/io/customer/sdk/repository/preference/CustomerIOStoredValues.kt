package io.customer.sdk.repository.preference

import io.customer.sdk.CustomerIOConfig
import io.customer.sdk.Version
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client

internal data class CustomerIOStoredValues(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val client: Client = Client.Android(sdkVersion = Version.version),
    val trackingApiUrl: String? = null,
    val autoTrackDeviceAttributes: Boolean = CustomerIOConfig.Companion.AnalyticsConstants.AUTO_TRACK_DEVICE_ATTRIBUTES,
    val logLevel: CioLogLevel = CustomerIOConfig.Companion.SDKConstants.LOG_LEVEL_DEFAULT,
    val backgroundQueueMinNumberOfTasks: Int = CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_MIN_NUMBER_OF_TASKS,
    val backgroundQueueSecondsDelay: Double = CustomerIOConfig.Companion.AnalyticsConstants.BACKGROUND_QUEUE_SECONDS_DELAY
) {
    constructor(customerIOConfig: CustomerIOConfig) : this(
        siteId = customerIOConfig.siteId,
        apiKey = customerIOConfig.apiKey,
        region = customerIOConfig.region,
        client = customerIOConfig.client,
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

internal fun CustomerIOStoredValues.doesExist(): Boolean = this.siteId.isNotEmpty()
