package io.customer.messaginginapp.gist.data.listeners

import io.customer.messaginginapp.state.InAppMessagingState
import java.security.MessageDigest

/**
 * The identity a queue request is made for, resolved the same way
 * [io.customer.messaginginapp.gist.data.NetworkUtilities.addCommonHeaders] picks the user token header,
 * within the session it was made in. Reset starts a new session, so a request from before a logout is
 * never shared with, or applied to, the session after it, even for the same user.
 */
internal data class QueueFetchKey(
    val userToken: String,
    val isAnonymous: Boolean,
    val sessionId: String = "",
    // Gist serves the queue of the person the JWT names, so a body fetched with one JWT is never
    // the answer for another.
    val userJwt: String? = null
) {
    /** Stored next to the cached queue response, so a 304 only reuses a body fetched for this identity. */
    val cacheOwner: String
        get() = "${if (isAnonymous) "anonymous" else "identified"}:$userToken" + userJwt?.let { ":jwt:${sha256(it)}" }.orEmpty()

    // The data class's toString would print the JWT.
    override fun toString(): String =
        "QueueFetchKey(userToken=$userToken, isAnonymous=$isAnonymous, sessionId=$sessionId, hasUserJwt=${userJwt != null})"

    companion object {
        fun from(state: InAppMessagingState): QueueFetchKey? {
            val userToken = state.userId ?: state.anonymousId ?: return null
            return QueueFetchKey(
                userToken = userToken,
                isAnonymous = state.userId == null,
                sessionId = state.sessionId,
                userJwt = state.activeUserJwt
            )
        }

        // The cache owner is persisted, and the JWT must not be.
        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Lets queue fetches for the same identity share one request.
 *
 * Several triggers fire close together at launch (the user-changed event, the process ON_START
 * catch-up), and each used to send its own request. While a request for an identity is in flight,
 * later fetches for that identity reuse it. A fetch that needs data from after it was requested
 * (`mustBeFresh`) can't be answered by an older request, so it schedules a single follow-up instead.
 * gist-web guards its queue request the same way.
 */
internal class QueueFetchCoordinator {
    /** Held by the caller that sends a request. Compared by reference, so every ticket is unique. */
    class Ticket(val key: QueueFetchKey)

    private var inFlight: Ticket? = null
    private var refetchWhenDone = false

    /**
     * Returns a ticket when the caller should send a request, or null when a request for the same
     * identity is already in flight.
     */
    @Synchronized
    fun begin(key: QueueFetchKey, mustBeFresh: Boolean): Ticket? {
        if (inFlight?.key == key) {
            if (mustBeFresh) {
                refetchWhenDone = true
            }
            return null
        }

        // Nothing in flight, or a request for a previous identity whose response will be
        // discarded: start a new one.
        return Ticket(key).also {
            inFlight = it
            refetchWhenDone = false
        }
    }

    /**
     * Releases the slot held by [ticket]. Returns true when a caller that needed newer data joined
     * the request, so one follow-up fetch is owed.
     */
    @Synchronized
    fun finish(ticket: Ticket): Boolean {
        // A request for another identity replaced this one; the slot is no longer ours.
        if (inFlight !== ticket) return false

        val refetch = refetchWhenDone
        inFlight = null
        refetchWhenDone = false
        return refetch
    }
}
