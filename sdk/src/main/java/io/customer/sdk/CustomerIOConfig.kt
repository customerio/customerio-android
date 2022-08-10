package io.customer.sdk

import io.customer.sdk.data.model.Region
import io.customer.sdk.data.store.Client
import io.customer.sdk.module.CustomerIOModuleConfig
import io.customer.sdk.util.CioLogLevel

data class CustomerIOConfig(
    val client: Client,
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val timeout: Long,
    val autoTrackScreenViews: Boolean,
    val autoTrackDeviceAttributes: Boolean,
    val autoTrackPushEvents: Boolean,
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
    /**
     * The number of seconds old a queue task is when it is "expired" and should be deleted.
     * We do not recommend modifying this value because it risks losing data or taking up too much space on the user's device.
     */
    val backgroundQueueTaskExpiredSeconds: Double,
    val logLevel: CioLogLevel,
    var trackingApiUrl: String?,
    val targetSdkVersion: Int,
    val configurations: Map<String, CustomerIOModuleConfig>
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
