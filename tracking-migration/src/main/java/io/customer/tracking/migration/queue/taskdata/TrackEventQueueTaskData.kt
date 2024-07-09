package io.customer.sdk.queue.taskdata

import io.customer.sdk.data.request.Event

internal data class TrackEventQueueTaskData(
    val identifier: String,
    val event: Event
)
