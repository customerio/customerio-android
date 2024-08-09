package io.customer.messaginginapp.domain

import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.presentation.GistSdk

data class InAppMessagingState(
    val isInitialized: Boolean = false,
    val pollingInterval: Long = GistSdk.pollInterval,
    val currentRoute: String = "",
    val currentUser: String? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val isModalVisible: Boolean = false,
    val currentModalMessage: Message? = null,
    val currentMessageBeingProcessed: Message? = null
)
