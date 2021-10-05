package io.customer.sdk

import io.customer.sdk.data.model.Region

class CustomerIoConfig(
    val siteId: String,
    val apiKey: String,
    val region: Region,
    val timeout: Long
)
