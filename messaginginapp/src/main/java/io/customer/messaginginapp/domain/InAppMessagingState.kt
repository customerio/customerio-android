package io.customer.messaginginapp.domain

import io.customer.messaginginapp.gist.data.model.Message

data class InAppMessagingState(
    val isInitialized: Boolean = false,
    val pollInterval: Long = 600_000L,
    val isAppInForeground: Boolean = false,
    val userId: String? = null,
    val currentRoute: String? = null,
    val currentMessage: Message? = null
)
