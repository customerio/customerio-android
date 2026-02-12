package io.customer.messaginginapp.gist.data.sse

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.customer.messaginginapp.gist.data.NetworkUtilities
import io.customer.messaginginapp.gist.data.model.InboxMessage
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.response.InboxMessageResponse
import io.customer.messaginginapp.gist.data.model.response.toDomain

internal class SseDataParser(
    private val sseLogger: InAppSseLogger,
    private val gson: Gson
) {
    /**
     * Parse in-app messages from SSE event data.
     *
     * @param data The JSON data string from the messages event
     * @return List of Message objects or empty list if parsing fails
     */
    fun parseInAppMessages(data: String): List<Message> {
        return parseMessageArray(data, Array<Message>::class.java)
    }

    /**
     * Parse inbox messages from SSE event data.
     *
     * @param data The JSON data string from the inbox_messages event
     * @return List of InboxMessage objects or empty list if parsing fails
     */
    fun parseInboxMessages(data: String): List<InboxMessage> {
        val response = parseMessageArray(data, Array<InboxMessageResponse>::class.java)
        return response.map { it.toDomain() }
    }

    /**
     * Generic method to parse message arrays from SSE event data.
     *
     * This method is resilient and will never throw exceptions.
     * Invalid or malformed data will result in an empty list being returned.
     *
     * @param data The JSON data string from the SSE event
     * @param arrayClass The class type of the array to parse
     * @return List of parsed objects or empty list if parsing fails
     */
    private fun <T> parseMessageArray(data: String, arrayClass: Class<Array<T>>): List<T> {
        if (data.isBlank()) {
            sseLogger.logReceivedEmptyMessageData()
            return emptyList()
        }

        return try {
            val messages = gson.fromJson(data, arrayClass)
            messages.toList()
        } catch (e: JsonSyntaxException) {
            sseLogger.logMessageParsingFailedInvalidJson(e.message, data)
            emptyList()
        } catch (e: Exception) {
            sseLogger.logMessageParsingError(e.message, data)
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
            sseLogger.logHeartbeatTimeoutNoData()
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
            sseLogger.logHeartbeatTimeoutParsingFailed(e.message, data)
            NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS
        } catch (e: Exception) {
            sseLogger.logHeartbeatTimeoutParsingError(e.message, data)
            NetworkUtilities.DEFAULT_HEARTBEAT_TIMEOUT_MS
        }
    }
}
