package io.customer.messaginginapp.gist.data.model.response

import com.google.gson.annotations.SerializedName
import io.customer.messaginginapp.gist.data.model.Message

/**
 * Response model for fetching user messages from the queue.
 */
internal data class QueueMessagesResponse(
    @SerializedName("inAppMessages")
    val inAppMessages: List<Message> = emptyList(),
    @SerializedName("inboxMessages")
    val inboxMessages: List<InboxMessageResponse> = emptyList()
)
