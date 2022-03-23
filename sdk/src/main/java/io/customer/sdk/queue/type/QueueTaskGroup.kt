package io.customer.sdk.queue.type

/**
 * All of the different groups that are possible in the queue.
 *
 * Not sure what queue groups are? See the `BACKGROUND_QUEUE.md` doc.
 */
sealed class QueueTaskGroup {
    /**
     * Be sure that the string is unique to each subclass of [QueueTaskGroup].
     * The returned string will identify each group in the background queue. Therefore, it's important to use properties from the subclass constructor in the returned string to differentiate groups of the same type.
     */
    data class IdentifyProfile(val identifier: String) : QueueTaskGroup() {
        override fun toString(): String = "identified_profile_$identifier"
    }
    data class RegisterPushToken(val token: String) : QueueTaskGroup() {
        override fun toString(): String = "registered_push_token_$token"
    }
}
