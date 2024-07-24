package io.customer.datapipelines.extensions

import io.customer.sdk.data.model.Region

// These URLs are for requesting CDP settings from the CDP API, and must align with server's CDP regional configuration.
fun Region.apiHost(): String {
    return when (this) {
        Region.US -> "cdp.customer.io/v1"
        Region.EU -> "cdp-eu.customer.io/v1"
    }
}

fun Region.cdnHost(): String {
    return when (this) {
        Region.US -> "cdp.customer.io/v1"
        Region.EU -> "cdp-eu.customer.io/v1"
    }
}
