package io.customer.messaginginapp.state

import io.customer.messaginginapp.gist.data.model.GistProperties
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.mockk.every
import io.mockk.mockk

/**
 * Creates a configurable, well-mocked Message for testing.
 * This ensures consistent mock behavior across tests.
 */
object MessageBuilderMock {
    /**
     * Create a fully mocked Message instance for testing.
     * All common properties are properly stubbed.
     */
    fun createMessage(
        messageId: String = java.util.UUID.randomUUID().toString(),
        queueId: String? = java.util.UUID.randomUUID().toString(),
        elementId: String? = null,
        routeRule: String? = null,
        persistent: Boolean = false,
        priority: Int = 1,
        position: MessagePosition = MessagePosition.CENTER
    ): Message {
        val message = mockk<Message>(relaxed = true)
        val gistProperties = mockk<GistProperties>(relaxed = true)

        every { message.messageId } returns messageId
        every { message.queueId } returns queueId
        every { message.priority } returns priority
        every { message.instanceId } returns java.util.UUID.randomUUID().toString()
        every { message.gistProperties } returns gistProperties

        every { gistProperties.elementId } returns elementId
        every { gistProperties.routeRule } returns routeRule
        every { gistProperties.persistent } returns persistent
        every { gistProperties.position } returns position

        every { message.embeddedElementId } returns elementId
        every { message.isEmbedded } returns (elementId != null)

        return message
    }
}
