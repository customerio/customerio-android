package io.customer.sdk.queue.type

import com.squareup.moshi.JsonClass
import io.customer.base.extenstions.unixTimeToDate
import io.customer.sdk.extensions.random
import java.util.*

// / Pointer to full queue task in persistent storage.
// / This data structure is meant to be as small as possible with the
// / ability to hold all queue task metadata in memory at runtime.
@JsonClass(generateAdapter = true)
data class QueueTaskMetadata(
    val taskPersistedId: String,
    val taskType: String,
    // The start of a new group of tasks.
    // Tasks can be the start of of 0 or 1 groups
    val groupStart: String?,
    // Groups that this task belongs to.
    // Tasks can belong to 0+ groups
    val groupMember: List<String>?,
    // Populated when the task is added to the queue.
    val createdAt: Date
) {
    companion object {
        val random: QueueTaskMetadata
            get() = QueueTaskMetadata(String.random, String.random, null, null, 1644600699L.unixTimeToDate())
    }
}
