package io.customer.messagingpush.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PushData(
    val link: String?,
    val image: String?
)
