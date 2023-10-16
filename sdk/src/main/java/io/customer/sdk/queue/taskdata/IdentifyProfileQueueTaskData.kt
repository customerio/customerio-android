@file:UseContextualSerialization(Any::class)

package io.customer.sdk.queue.taskdata

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization

@Serializable
data class IdentifyProfileQueueTaskData(
    val identifier: String,
    @Contextual val attributes: Map<String, Any>
)
