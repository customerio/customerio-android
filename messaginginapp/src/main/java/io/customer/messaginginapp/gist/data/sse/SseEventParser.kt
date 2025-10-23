package io.customer.messaginginapp.gist.data.sse

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.sdk.core.util.Logger

internal class SseEventParser(
    private val logger: Logger,
    private val gson: Gson
) {
    /**
     * Parse messages from SSE event data.
     * 
     * This method is resilient and will never throw exceptions.
     * Invalid or malformed data will result in an empty list being returned.
     * 
     * @param data The JSON data string from the messages event
     * @return List of Message objects or empty list if parsing fails
     */
    fun parseMessages(data: String): List<Message> {
        if (data.isBlank()) {
            logger.debug("SSE: Received empty or blank message data")
            return emptyList()
        }
        
        return try {
            val messages = gson.fromJson(data, Array<Message>::class.java)
            messages.toList()
        } catch (e: JsonSyntaxException) {
            logger.debug("SSE: Failed to parse messages - invalid JSON: ${e.message}, data: $data")
            emptyList()
        } catch (e: Exception) {
            logger.debug("SSE: Error parsing messages: ${e.message}, data: $data")
            emptyList()
        }
    }
}

/**
 * Represents a parsed SSE event.
 * 
 * @property eventType The type of event (connected, heartbeat, messages, ttl_exceeded)
 * @property data The JSON data associated with the event
 */
internal data class SseEvent(
    val eventType: String,
    val data: String
)
