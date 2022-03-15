package io.customer.sdk.queue.taskdata

import com.squareup.moshi.JsonClass
import io.customer.sdk.data.request.Event

@JsonClass(generateAdapter = true)
internal data class TrackEventQueueTaskData(
    val identifier: String,
    val event: Event
)
