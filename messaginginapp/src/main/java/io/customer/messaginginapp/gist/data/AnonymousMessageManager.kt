package io.customer.messaginginapp.gist.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.customer.messaginginapp.di.inAppMessagingManager
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.model.BroadcastFrequency
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.isMessageAnonymous
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.util.concurrent.TimeUnit

internal interface AnonymousMessageManager {
    fun updateAnonymousMessagesLocalStore(messages: List<Message>)
    fun getEligibleAnonymousMessages(): List<Message>
    fun markAnonymousAsSeen(anonymousId: String)
    fun markAnonymousAsDismissed(anonymousId: String)
}

internal class AnonymousMessageManagerImpl() : AnonymousMessageManager {

    private val inAppMessagingManager = SDKComponent.inAppMessagingManager
    private val state: InAppMessagingState
        get() = inAppMessagingManager.getCurrentState()
    private val inAppPreferenceStore: InAppPreferenceStore
        get() = SDKComponent.inAppPreferenceStore
    private val logger: Logger = SDKComponent.logger
    private val gson = Gson()

    companion object {
        private const val ANONYMOUS_MESSAGES_EXPIRY_MINUTES = 60L
        private val ANONYMOUS_MESSAGE_LIST_TYPE = object : TypeToken<List<Message>>() {}.type
    }

    override fun updateAnonymousMessagesLocalStore(messages: List<Message>) {
        if (!hasValidUserToken()) return

        val messagesWithAnonymous = messages.filter { it.isMessageAnonymous() }

        if (messagesWithAnonymous.isNotEmpty()) {
            val previousAnonymousMessages = getParsedAnonymousMessages()

            // Server has anonymous messages - update local storage
            val expiryTimeMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(ANONYMOUS_MESSAGES_EXPIRY_MINUTES)
            val messagesJson = gson.toJson(messagesWithAnonymous)
            inAppPreferenceStore.saveAnonymousMessages(messagesJson, expiryTimeMillis)

            // Clean up tracking data for anonymous messages no longer in server response
            cleanupExpiredAnonymousTracking(messagesWithAnonymous, previousAnonymousMessages)

            logger.debug("Saved ${messagesWithAnonymous.size} anonymous messages to local store")
        } else {
            // Server has no anonymous messages - they've expired, remove locally
            // Anonymous messages are sticky, so absence means they're no longer active
            logger.debug("No anonymous messages in server response - clearing local storage as anonymous messages have expired")
            clearAllAnonymousData()
        }
    }

    override fun getEligibleAnonymousMessages(): List<Message> {
        if (!hasValidUserToken()) return emptyList()

        val anonymousMessages = getParsedAnonymousMessages()

        return anonymousMessages.filter { anonymousMessage ->
            val queueId = anonymousMessage.queueId ?: return@filter false

            // Get frequency details directly from message
            val frequency = getFrequencyFor(anonymousMessage) ?: return@filter false

            // Validate frequency values
            if (frequency.count < 0 || frequency.delay < 0) {
                return@filter false
            }

            // Check if dismissed (and not ignoreDismiss)
            if (inAppPreferenceStore.isAnonymousDismissed(queueId) && !frequency.ignoreDismiss) {
                return@filter false
            }

            // Check if in delay period (temporary restriction after being shown)
            if (inAppPreferenceStore.isAnonymousInDelayPeriod(queueId)) {
                return@filter false
            }

            // Check frequency limits
            val numberOfTimesShown = inAppPreferenceStore.getAnonymousTimesShown(queueId)
            val isFrequencyUnlimited = frequency.count == 0

            if (isFrequencyUnlimited) {
                true
            } else {
                numberOfTimesShown < frequency.count
            }
        }
    }

    override fun markAnonymousAsSeen(anonymousId: String) {
        logger.debug("Marking anonymous message $anonymousId as seen")
        if (!hasValidUserToken()) return

        val anonymousDetails = getAnonymousFrequency(anonymousId) ?: run {
            logger.debug("Could not find anonymous message details for $anonymousId")
            return
        }

        inAppPreferenceStore.incrementAnonymousTimesShown(anonymousId)
        val numberOfTimesShown = inAppPreferenceStore.getAnonymousTimesShown(anonymousId)

        when {
            anonymousDetails.count == 1 -> {
                inAppPreferenceStore.setAnonymousDismissed(anonymousId, true)
                logger.debug("Marked anonymous message $anonymousId as permanently dismissed (count=1)")
            }
            anonymousDetails.delay > 0 -> {
                val nextShowTimeMillis = System.currentTimeMillis() + (anonymousDetails.delay * 1000L)
                inAppPreferenceStore.setAnonymousNextShowTime(anonymousId, nextShowTimeMillis)
                logger.debug("Marked anonymous message $anonymousId as seen, shown $numberOfTimesShown times, next show time: ${java.util.Date(nextShowTimeMillis)}")
            }
            else -> {
                logger.debug("Marked anonymous message $anonymousId as seen, shown $numberOfTimesShown times, no delay restriction")
            }
        }
    }

    override fun markAnonymousAsDismissed(anonymousId: String) {
        logger.debug("Marking anonymous message $anonymousId as dismissed")
        if (!hasValidUserToken()) return

        val anonymousDetails = getAnonymousFrequency(anonymousId) ?: run {
            logger.debug("Could not find anonymous message details for $anonymousId")
            return
        }

        // Check ignoreDismiss flag from message
        if (anonymousDetails.ignoreDismiss) {
            logger.debug("Anonymous message $anonymousId is set to ignore dismiss")
            return
        }

        // Mark as dismissed
        inAppPreferenceStore.setAnonymousDismissed(anonymousId, true)
        logger.debug("Marked anonymous message $anonymousId as dismissed and will not show again")
    }

    private fun getParsedAnonymousMessages(): List<Message> {
        val anonymousMessagesJson = inAppPreferenceStore.getAnonymousMessages() ?: return emptyList()

        return try {
            gson.fromJson<List<Message>>(anonymousMessagesJson, ANONYMOUS_MESSAGE_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            logger.debug("Error parsing stored anonymous messages: ${e.message}")
            emptyList()
        }
    }

    private fun getFrequencyFor(message: Message): BroadcastFrequency? {
        return message.gistProperties.broadcast?.frequency
    }

    private fun cleanupExpiredAnonymousTracking(currentAnonymousMessages: List<Message>, previousAnonymousMessages: List<Message>) {
        val currentAnonymousIds = currentAnonymousMessages.mapNotNull { it.queueId }.toSet()
        val previousAnonymousIds = previousAnonymousMessages.mapNotNull { it.queueId }.toSet()
        val expiredAnonymousIds = previousAnonymousIds - currentAnonymousIds

        expiredAnonymousIds.forEach { expiredId ->
            inAppPreferenceStore.clearAnonymousTracking(expiredId)
            logger.debug("Cleaned up tracking data for expired anonymous message: $expiredId")
        }
    }

    private fun clearAllAnonymousData() {
        inAppPreferenceStore.clearAllAnonymousData()
        logger.debug("Cleared all anonymous message storage")
    }

    private fun getAnonymousFrequency(anonymousId: String): BroadcastFrequency? {
        val anonymousMessages = getParsedAnonymousMessages()
        val anonymousMessage = anonymousMessages.find { it.queueId == anonymousId }
        return anonymousMessage?.gistProperties?.broadcast?.frequency
    }

    private fun hasValidUserToken(): Boolean {
        val userToken = state.userId ?: state.anonymousId
        if (userToken == null) {
            logger.debug("No user token available for anonymous message management")
            return false
        }
        return true
    }
}
