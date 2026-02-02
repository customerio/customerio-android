package io.customer.messaginginapp.gist.data.model

import com.google.gson.annotations.SerializedName

/**
 * Response model for fetching user messages from the queue.
 */
internal data class QueueMessagesResponse(
    @SerializedName("inAppMessages")
    val inAppMessages: List<Message> = emptyList(),
    @SerializedName("inboxMessages")
    val inboxMessages: List<InboxMessage> = emptyList()
)
