package io.customer.sdk.data.model

/**
 * Region that your Customer.io Workspace is located in.
 * The SDK will route traffic to the correct data center location depending on the `Region` that you use.
 */
sealed class Region(val code: String, val baseUrl: String) {
    object US : Region(code = "us", baseUrl = "https://track.customer.io/")
    object EU : Region(code = "eu", baseUrl = "https://track-eu.customer.io/")
}
