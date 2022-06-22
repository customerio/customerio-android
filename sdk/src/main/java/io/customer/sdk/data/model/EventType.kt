package io.customer.sdk.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
enum class EventType {
    event, screen; // ktlint-disable enum-entry-name-case (enum cases used for JSON values)
}
