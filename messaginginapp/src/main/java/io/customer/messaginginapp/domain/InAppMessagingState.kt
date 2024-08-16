package io.customer.messaginginapp.domain

import android.content.Context
import io.customer.messaginginapp.gist.GistEnvironment
import io.customer.messaginginapp.gist.data.model.Message

data class InAppMessagingState(
    val context: Context? = null,
    val siteId: String = "",
    val dataCenter: String = "",
    val environment: GistEnvironment = GistEnvironment.PROD,
    val pollInterval: Long = 600_000L,
    val isAppInForeground: Boolean = false,
    val userId: String? = null,
    val currentRoute: String? = null,
    val currentMessage: Message? = null,
    val messagesInQueue: Set<Message> = setOf(),
    val shownMessageQueueIds: Set<String> = setOf()
)
