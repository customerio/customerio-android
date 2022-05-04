package io.customer.sdk.data.model

/**
 * Region that your Customer.io Workspace is located in.
 * The SDK will route traffic to the correct data center location depending on the `Region` that you use.
 */
sealed class Region(val code: String, val baseUrl: String) {

    // Note: These URLs are meant to be used specifically by the official
    // mobile SDKs. View our API docs: https://customer.io/docs/api/
    // to find the correct hostname for what you're trying to do.
    object US : Region(code = "us", baseUrl = "https://track-sdk.customer.io/")
    object EU : Region(code = "eu", baseUrl = "https://track-sdk-eu.customer.io/")

    // Utilise custom `baseUrl` provided and ignore `code`
    class Custom(baseUrl: String) : Region(code = "", baseUrl = baseUrl)
}
