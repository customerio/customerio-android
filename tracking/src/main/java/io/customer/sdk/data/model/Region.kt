package io.customer.sdk.data.model

/**
 * Region that your Customer.io Workspace is located in.
 * The SDK will route traffic to the correct data center location depending on the `Region` that you use.
 */
sealed class Region(val code: String) {

    // Note: These URLs are meant to be used specifically by the official
    // mobile SDKs. View our API docs: https://customer.io/docs/api/
    // to find the correct hostname for what you're trying to do.
    object US : Region(code = "us")
    object EU : Region(code = "eu")

    companion object {
        fun getRegion(region: String?, fallback: Region = US): Region {
            return if (region.isNullOrBlank()) {
                fallback
            } else {
                listOf(
                    US,
                    EU
                ).find { value -> value.code.equals(region, ignoreCase = true) } ?: fallback
            }
        }
    }
}
