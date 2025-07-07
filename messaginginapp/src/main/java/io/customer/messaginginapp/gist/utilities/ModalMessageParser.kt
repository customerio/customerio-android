package io.customer.messaginginapp.gist.utilities

import android.content.Intent
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
    suspend fun parseExtras(intent: Intent): ModalMessageExtras?

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
    override suspend fun parseExtras(intent: Intent): ModalMessageExtras? {
        val rawMessage = intent.getStringExtra(ModalMessageParser.EXTRA_IN_APP_MESSAGE)
        if (rawMessage.isNullOrEmpty()) {
            logger.error("ModalMessageParser: Message is null or empty")
            return null
        }

        return withContext(dispatchersProvider.background) {
            try {
                val message = requireNotNull(parser.parseMessageFromJson(rawMessage))
                val rawPosition = intent.getStringExtra(ModalMessageParser.EXTRA_IN_APP_MODAL_POSITION)
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
