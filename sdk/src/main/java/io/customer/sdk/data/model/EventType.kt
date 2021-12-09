package io.customer.sdk.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
enum class EventType {
    event, screen
}
