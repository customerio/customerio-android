package io.customer.sdk

import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region

class CustomerIOConfig(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val timeout: Long,
    val urlHandler: CustomerIOUrlHandler?,
    val autoTrackScreenViews: Boolean,
    /**
     Number of tasks in the background queue before the queue begins operating.
     This is mostly used during development to test configuration is setup. We do not recommend
     modifying this value because it impacts battery life of mobile device.
     */
    val backgroundQueueMinNumberOfTasks: Int,
    /**
     * The number of seconds to delay running queue after a task has been added to it.
     * We do not recommend modifying this value because it impacts battery life of mobile device.
     */
    val backgroundQueueSecondsDelay: Long
)
