package io.customer.sdk.communication

import java.util.Date
import java.util.UUID

/**
 * Class to represent an event that can be published and subscribed to using [EventBus].
 */
sealed class Event {
    // Unique identifier for the event
    val storageId: String = UUID.randomUUID().toString()

    // Metadata associated with the event
    val params: Map<String, String> = emptyMap()

    // Timestamp when the event was created
    val timestamp: Date = Date()

    // Dummy event for testing purposes
    class DummyEvent(val message: String) : Event()

    // Dummy event for testing purposes
    class DummyEmptyEvent : Event()
}
