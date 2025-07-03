package io.customer.messaginginapp.gist.utilities

import com.google.gson.Gson
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.MessagePosition
import io.customer.sdk.core.util.DispatchersProvider
import io.customer.sdk.core.util.Logger
import kotlinx.coroutines.withContext

/**
 * Parsed modal message data ready for display, including message content and position.
 */
internal data class ModalMessageExtras(
    val message: Message,
    val messagePosition: MessagePosition
)

/**
 * Interface for parsing modal messages from extras like Intent or Bundle.
 * It provides methods to extract message data and position from extras.
 */
internal interface ModalMessageParser {
    suspend fun parseExtras(provider: ExtrasProvider): ModalMessageExtras?

    /**
     * Abstraction for accessing string values by key from extras like Intent or Bundle.
     */
    interface ExtrasProvider {
        fun getString(key: String): String?
    }

    /**
     * Abstraction for parsing JSON strings into Message objects.
     */
    interface JsonParser {
        @Throws(Exception::class)
        fun parseMessageFromJson(json: String): Message?
    }

    companion object {
        const val EXTRA_IN_APP_MESSAGE: String = "GIST_MESSAGE"
        const val EXTRA_IN_APP_MODAL_POSITION: String = "GIST_MODAL_POSITION"
    }
}

/**
 * Default JSON parser implementation using Gson for modal message deserialization.
 */
internal class ModalMessageGsonParser(
    private val gson: Gson
) : ModalMessageParser.JsonParser {
    @Throws(Exception::class)
    override fun parseMessageFromJson(json: String): Message? {
        return gson.fromJson(json, Message::class.java)
    }
}

/**
 * Default implementation of ModalMessageParser to be used in production environments.
 */
internal class ModalMessageParserDefault(
    private val logger: Logger,
    private val dispatchersProvider: DispatchersProvider,
    private val parser: ModalMessageParser.JsonParser
) : ModalMessageParser {
    override suspend fun parseExtras(provider: ModalMessageParser.ExtrasProvider): ModalMessageExtras? {
        val rawMessage = provider.getString(ModalMessageParser.EXTRA_IN_APP_MESSAGE)
        if (rawMessage.isNullOrEmpty()) {
            logger.error("ModalMessageParser: Message is null or empty")
            return null
        }

        return withContext(dispatchersProvider.background) {
            try {
                val message = parser.parseMessageFromJson(rawMessage)
                if (message == null) {
                    logger.error("ModalMessageParser: Message parsing failed for: $rawMessage")
                    return@withContext null
                }

                val rawPosition = provider.getString(ModalMessageParser.EXTRA_IN_APP_MODAL_POSITION)
                val position = if (rawPosition == null) {
                    message.gistProperties.position
                } else {
                    MessagePosition.valueOf(rawPosition.uppercase())
                }

                return@withContext ModalMessageExtras(
                    message = message,
                    messagePosition = position
                )
            } catch (ex: Exception) {
                logger.error("ModalMessageParser: Failed to parse modal message with error: ${ex.message}")
                return@withContext null
            }
        }
    }
}
