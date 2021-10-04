package io.customer.sdk.api.model

sealed class Region(val code: String, val baseUrl: String) {
    object US : Region(code = "us", baseUrl = "https://track.customer.io/api/v1")
    object EU : Region(code = "eu", baseUrl = "https://track-eu.customer.io/api/v1")
}
