package io.customer.messaginginapp.gist.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.model.Message
import io.customer.messaginginapp.gist.data.model.isMessageBroadcast
import io.customer.messaginginapp.state.InAppMessagingState
import io.customer.messaginginapp.store.InAppPreferenceStore
import io.customer.sdk.core.di.SDKComponent
import io.customer.sdk.core.util.Logger
import java.util.concurrent.TimeUnit

interface BroadcastMessageManager {
    fun updateBroadcastsLocalStore(messages: List<Message>)
    fun getEligibleBroadcasts(): List<Message>
    fun markBroadcastAsSeen(broadcastId: String)
    fun markBroadcastAsDismissed(broadcastId: String)
}

internal class BroadcastMessageManagerImpl(
    private val state: InAppMessagingState
) : BroadcastMessageManager {

    private val inAppPreferenceStore: InAppPreferenceStore
        get() = SDKComponent.inAppPreferenceStore
    private val logger: Logger = SDKComponent.logger
    private val gson = Gson()

    companion object {
        private const val BROADCASTS_EXPIRY_MINUTES = 60L
    }

    override fun updateBroadcastsLocalStore(messages: List<Message>) {
        if (!hasValidUserToken()) return

        val messagesWithBroadcast = messages.filter { it.isMessageBroadcast() }

        if (messagesWithBroadcast.isNotEmpty()) {
            // Server has broadcasts - update local storage
            val expiryTimeMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(BROADCASTS_EXPIRY_MINUTES)
            val messagesJson = gson.toJson(messagesWithBroadcast)
            inAppPreferenceStore.saveBroadcastMessages(messagesJson, expiryTimeMillis)
            logger.debug("Saved ${messagesWithBroadcast.size} broadcast messages to local store")
        } else {
            // Server has no broadcasts - they've expired, remove locally
            // Broadcasts are sticky, so absence means they're no longer active
            logger.debug("No broadcast messages in server response - clearing local storage as broadcasts have expired")
            clearAllBroadcastData()
        }
    }

    override fun getEligibleBroadcasts(): List<Message> {
        if (!hasValidUserToken()) return emptyList()

        val broadcastsJson = inAppPreferenceStore.getBroadcastMessages() ?: return emptyList()

        return try {
            val listType = object : TypeToken<List<Message>>() {}.type
            val broadcasts: List<Message> = gson.fromJson(broadcastsJson, listType)

            broadcasts.filter { broadcast ->
                val broadcastDetails = broadcast.gistProperties.broadcast?.frequency ?: return@filter false
                val queueId = broadcast.queueId ?: return@filter false

                // Check if dismissed (and not ignoreDismiss)
                if (inAppPreferenceStore.isBroadcastDismissed(queueId) && !broadcastDetails.ignoreDismiss) {
                    return@filter false
                }

                // Check frequency limits
                val numberOfTimesShown = inAppPreferenceStore.getBroadcastTimesShown(queueId)
                val isFrequencyUnlimited = broadcastDetails.count == 0

                // Show if unlimited or under count limit
                isFrequencyUnlimited || numberOfTimesShown < broadcastDetails.count
            }
        } catch (e: Exception) {
            logger.debug("Error parsing stored broadcast messages: ${e.message}")
            emptyList()
        }
    }

    override fun markBroadcastAsSeen(broadcastId: String) {
        logger.debug("Marking broadcast $broadcastId as seen")
        if (!hasValidUserToken()) return

        // Simply increment the times shown counter
        inAppPreferenceStore.incrementBroadcastTimesShown(broadcastId)
        logger.debug("Incremented seen count for broadcast $broadcastId")
    }

    override fun markBroadcastAsDismissed(broadcastId: String) {
        logger.debug("Marking broadcast $broadcastId as dismissed")
        if (!hasValidUserToken()) return

        val broadcast = fetchMessageBroadcast(broadcastId) ?: return
        val broadcastDetails = broadcast.gistProperties.broadcast?.frequency ?: return

        if (broadcastDetails.ignoreDismiss) {
            logger.debug("Broadcast $broadcastId is set to ignore dismiss")
            return
        }

        // Mark as dismissed
        inAppPreferenceStore.setBroadcastDismissed(broadcastId, true)
        logger.debug("Marked broadcast $broadcastId as dismissed and will not show again")
    }

    private fun fetchMessageBroadcast(broadcastId: String): Message? {
        val broadcastsJson = inAppPreferenceStore.getBroadcastMessages() ?: return null

        return try {
            val listType = object : TypeToken<List<Message>>() {}.type
            val broadcasts: List<Message> = gson.fromJson(broadcastsJson, listType)
            broadcasts.find { it.queueId == broadcastId }
        } catch (e: Exception) {
            logger.debug("Error fetching broadcast message $broadcastId: ${e.message}")
            null
        }
    }

    private fun clearAllBroadcastData() {
        inAppPreferenceStore.clearAllBroadcastData()
        logger.debug("Cleared all broadcast message storage")
    }

    private fun hasValidUserToken(): Boolean {
        val userToken = state.userId ?: state.anonymousId
        if (userToken == null) {
            logger.debug("No user token available for broadcast message management")
            return false
        }
        return true
    }
}
