package io.customer.messaginginapp.gist.data.sse

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.sdk.core.util.Logger

internal class SseDataParser(
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

    /**
     * Parse heartbeat timeout from heartbeat event data.
     *
     * @param data JSON data from heartbeat event (e.g., {"heartbeat":30})
     * @return Timeout value in milliseconds, or default if parsing fails
     */
    fun parseHeartbeatTimeout(data: String): Long {
        if (data.isBlank()) {
            logger.debug("SSE: Heartbeat event has no data, using default timeout")
            return NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS
        }

        return try {
            val jsonObject = gson.fromJson(data, JsonObject::class.java)
            val heartbeatSeconds = jsonObject.get("heartbeat")?.asInt
            val result = if (heartbeatSeconds != null) {
                (heartbeatSeconds * 1000).toLong() // Convert seconds to milliseconds
            } else {
                NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS
            }
            result
        } catch (e: JsonSyntaxException) {
            logger.debug("SSE: Failed to parse heartbeat timeout - invalid JSON: ${e.message}, data: $data")
            NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS
        } catch (e: Exception) {
            logger.debug("SSE: Error parsing heartbeat timeout: ${e.message}, data: $data")
            NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS
        }
    }
}
