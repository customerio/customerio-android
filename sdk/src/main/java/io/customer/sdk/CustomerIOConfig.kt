package io.customer.sdk

import io.customer.sdk.data.communication.CustomerIOUrlHandler
import io.customer.sdk.data.model.Region

data class CustomerIOConfig(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val timeout: Long,
    val urlHandler: CustomerIOUrlHandler?,
    val autoTrackScreenViews: Boolean,
    val autoTrackDeviceAttributes: Boolean,
)
