package io.customer.messaginginapp.type

/**
 * Why Customer.io rejected the user JWT handed over with
 * [io.customer.messaginginapp.ModuleMessagingInApp.setUserJwt].
 */
enum class UserJwtRejectionReason(val value: String) {
    /** No JWT was sent while the workspace requires one. */
    MISSING("missing"),

    /** The JWT isn't three base64url parts, or its header or payload is unreadable, or `exp` is missing. */
    MALFORMED("malformed"),

    /** The signature doesn't verify with the workspace's signing key, or `alg` isn't RS256. */
    BAD_SIGNATURE("bad_signature"),

    /** `exp` has passed, or `nbf` is still ahead. Fetch a fresh token. */
    EXPIRED("expired"),

    /** `sub` isn't `cio_<cio_id>`. */
    BAD_SUBJECT("bad_subject"),

    /** A reason this SDK version doesn't know. */
    UNKNOWN("unknown")
}

/**
 * Notified when Customer.io rejects the user JWT. In-app and inbox fetches for that user pause
 * until a different JWT is handed over, so the listener is called once per JWT.
 */
fun interface UserJwtListener {
    /**
     * @param userId the identified user the rejected JWT was handed over for.
     * @param reason why it was rejected. For [UserJwtRejectionReason.EXPIRED] or
     * [UserJwtRejectionReason.MISSING], fetch a fresh token from your backend and hand it over.
     */
    fun onUserJwtRejected(userId: String, reason: UserJwtRejectionReason)
}
