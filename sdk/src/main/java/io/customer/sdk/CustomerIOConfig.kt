package io.customer.sdk

import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region
import io.customer.sdk.util.CioLogLevel

data class CustomerIOConfig(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val timeout: Long,
    val urlHandler: CustomerIOUrlHandler?,
    val autoTrackScreenViews: Boolean,
    val autoTrackDeviceAttributes: Boolean,
    /**
     * Number of tasks in the background queue before the queue begins operating.
     * This is mostly used during development to test configuration is setup. We do not recommend
     * modifying this value because it impacts battery life of mobile device.
     */
    val backgroundQueueMinNumberOfTasks: Int,
    /**
     * The number of seconds to delay running queue after a task has been added to it.
     * We do not recommend modifying this value because it impacts battery life of mobile device.
     */

    val backgroundQueueSecondsDelay: Double,
    val logLevel: CioLogLevel,
    /**
     * Base URL to use for the Customer.io track API. You will more then likely not modify this value.
     If you override this value, `Region` set when initializing the SDK will be ignored.
     */
    var trackingApiUrl: String? = null
) {
    internal val trackingApiHostname: String
        get() {
            return this.trackingApiUrl ?: this.region.let { selectedRegion ->
                when (selectedRegion) {
                    Region.US -> "https://track-sdk.customer.io/"
                    Region.EU -> "https://track-sdk-eu.customer.io/"
                }
            }
        }
}
