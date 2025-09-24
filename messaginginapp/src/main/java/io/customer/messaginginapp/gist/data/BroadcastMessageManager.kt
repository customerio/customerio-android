package io.customer.messaginginapp.gist.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.customer.messaginginapp.di.inAppPreferenceStore
import io.customer.messaginginapp.gist.data.model.BroadcastFrequency
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
        private val BROADCAST_LIST_TYPE = object : TypeToken<List<Message>>() {}.type
    }

    override fun updateBroadcastsLocalStore(messages: List<Message>) {
        if (!hasValidUserToken()) return

        val messagesWithBroadcast = messages.filter { it.isMessageBroadcast() }

        if (messagesWithBroadcast.isNotEmpty()) {
            val previousBroadcasts = getParsedBroadcastMessages()

            // Server has broadcasts - update local storage
            val expiryTimeMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(BROADCASTS_EXPIRY_MINUTES)
            val messagesJson = gson.toJson(messagesWithBroadcast)
            inAppPreferenceStore.saveBroadcastMessages(messagesJson, expiryTimeMillis)

            // Store ignoreDismiss separately as it can be modified independently
            messagesWithBroadcast.forEach { message ->
                message.queueId?.let { queueId ->
                    val frequency = message.gistProperties.broadcast?.frequency
                    if (frequency != null) {
                        // Validate frequency values and skip invalid ones
                        if (frequency.count >= 0 && frequency.delay >= 0) {
                            inAppPreferenceStore.setBroadcastIgnoreDismiss(queueId, frequency.ignoreDismiss)
                        } else {
                            logger.debug("Skipping broadcast $queueId with invalid frequency values: count=${frequency.count}, delay=${frequency.delay}")
                        }
                    }
                }
            }

            // Clean up tracking data for broadcasts no longer in server response
            cleanupExpiredBroadcastTracking(messagesWithBroadcast, previousBroadcasts)

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

        val broadcasts = getParsedBroadcastMessages()

        return broadcasts.filter { broadcast ->
            val queueId = broadcast.queueId ?: return@filter false

            // Get frequency details directly from message
            val frequency = getFrequencyFor(broadcast) ?: return@filter false

            // Validate frequency values
            if (frequency.count < 0 || frequency.delay < 0) {
                return@filter false
            }

            // Check if dismissed (and not ignoreDismiss)
            if (inAppPreferenceStore.isBroadcastDismissed(queueId) && !frequency.ignoreDismiss) {
                return@filter false
            }

            // Check if in delay period (temporary restriction after being shown)
            if (inAppPreferenceStore.isBroadcastInDelayPeriod(queueId)) {
                return@filter false
            }

            // Check frequency limits
            val numberOfTimesShown = inAppPreferenceStore.getBroadcastTimesShown(queueId)
            val isFrequencyUnlimited = frequency.count == 0

            // Show if unlimited or under count limit
            isFrequencyUnlimited || numberOfTimesShown < frequency.count
        }
    }

    override fun markBroadcastAsSeen(broadcastId: String) {
        logger.debug("Marking broadcast $broadcastId as seen")
        if (!hasValidUserToken()) return

        // Get broadcast details to check delay configuration
        val broadcasts = getParsedBroadcastMessages()
        val broadcast = broadcasts.find { it.queueId == broadcastId }
        val broadcastDetails = broadcast?.gistProperties?.broadcast?.frequency

        if (broadcastDetails == null) {
            logger.debug("Could not find broadcast details for $broadcastId")
            return
        }

        // Increment the times shown counter
        inAppPreferenceStore.incrementBroadcastTimesShown(broadcastId)
        val numberOfTimesShown = inAppPreferenceStore.getBroadcastTimesShown(broadcastId)

        // Apply delay logic aligned with web SDK
        when {
            broadcastDetails.count == 1 -> {
                // Permanent dismissal for single-show broadcasts
                inAppPreferenceStore.setBroadcastDismissed(broadcastId, true)
                logger.debug("Marked broadcast $broadcastId as permanently dismissed (count=1)")
            }
            broadcastDetails.delay > 0 -> {
                // Temporary restriction with delay
                val nextShowTimeMillis = System.currentTimeMillis() + (broadcastDetails.delay * 1000L)
                inAppPreferenceStore.setBroadcastNextShowTime(broadcastId, nextShowTimeMillis)
                logger.debug("Marked broadcast $broadcastId as seen, shown $numberOfTimesShown times, next show time: ${java.util.Date(nextShowTimeMillis)}")
            }
            else -> {
                // No delay, can show again immediately (subject to frequency limits)
                logger.debug("Marked broadcast $broadcastId as seen, shown $numberOfTimesShown times, no delay restriction")
            }
        }
    }

    override fun markBroadcastAsDismissed(broadcastId: String) {
        logger.debug("Marking broadcast $broadcastId as dismissed")
        if (!hasValidUserToken()) return

        // Check ignoreDismiss flag from preferences
        val ignoreDismiss = inAppPreferenceStore.getBroadcastIgnoreDismiss(broadcastId)
        if (ignoreDismiss) {
            logger.debug("Broadcast $broadcastId is set to ignore dismiss")
            return
        }

        // Mark as dismissed
        inAppPreferenceStore.setBroadcastDismissed(broadcastId, true)
        logger.debug("Marked broadcast $broadcastId as dismissed and will not show again")
    }

    private fun getParsedBroadcastMessages(): List<Message> {
        val broadcastsJson = inAppPreferenceStore.getBroadcastMessages() ?: return emptyList()

        return try {
            gson.fromJson<List<Message>>(broadcastsJson, BROADCAST_LIST_TYPE) ?: emptyList()
        } catch (e: Exception) {
            logger.debug("Error parsing stored broadcast messages: ${e.message}")
            emptyList()
        }
    }

    private fun getFrequencyFor(message: Message): BroadcastFrequency? {
        return message.gistProperties.broadcast?.frequency
    }

    private fun cleanupExpiredBroadcastTracking(currentBroadcasts: List<Message>, previousBroadcasts: List<Message>) {
        // Find broadcasts that were previously stored but are no longer in server response
        val currentBroadcastIds = currentBroadcasts.mapNotNull { it.queueId }.toSet()
        val expiredBroadcastIds = previousBroadcasts.mapNotNull { it.queueId } - currentBroadcastIds

        // Clean up tracking data for expired broadcasts
        expiredBroadcastIds.forEach { expiredId ->
            inAppPreferenceStore.clearBroadcastTracking(expiredId)
            logger.debug("Cleaned up tracking data for expired broadcast: $expiredId")
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
