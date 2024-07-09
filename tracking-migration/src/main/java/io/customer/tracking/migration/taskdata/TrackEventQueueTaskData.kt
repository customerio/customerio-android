package io.customer.tracking.migration.taskdata

import io.customer.tracking.migration.request.Event

internal data class TrackEventQueueTaskData(
    val identifier: String,
    val event: Event
)
