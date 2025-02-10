package io.customer.sdk.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(val writeKey: String, val apiHost: String)
