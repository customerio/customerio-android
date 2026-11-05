package io.customer.messagingpush.processor

import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload
import io.customer.messagingpush.util.PushTrackingUtil

internal sealed class BackgroundPushParseResult {
    /** Not a silent push, so the regular push handling applies. */
    object NotBackground : BackgroundPushParseResult()

    /** Marked as a silent push, but not one this SDK can deliver. It is dropped without display. */
    object Unsupported : BackgroundPushParseResult()

    class Received(val payload: CustomerIOBackgroundPushPayload) : BackgroundPushParseResult()
}

internal object BackgroundPushParser {
    private const val VERSION_KEY = "CIO-Background-Version"
    private const val ID_KEY = "CIO-Background-Delivery-ID"
    private const val TOKEN_KEY = "CIO-Background-Delivery-Token"
    private val metadataKeys = setOf(VERSION_KEY, ID_KEY, TOKEN_KEY)

    // Lowercase. Customer.io never sends these keys in a silent push. Firebase strips the exact spellings
    // of from, message_type, collapse_key and the google. and gcm. prefixes, so the checks for those here
    // and in the data filter below only catch other casings.
    private val reservedKeys = setOf("aps", "cio", "notification", "from", "message_type", "collapse_key")

    fun parse(message: RemoteMessage): BackgroundPushParseResult {
        val data = message.data
        // A complete legacy pair always gets the regular push handling.
        if (data[PushTrackingUtil.DELIVERY_ID_KEY] != null && data[PushTrackingUtil.DELIVERY_TOKEN_KEY] != null) {
            return BackgroundPushParseResult.NotBackground
        }
        // Only the exact version key marks a silent push, so another sender's stray delivery key stays app data.
        if (VERSION_KEY !in data) return BackgroundPushParseResult.NotBackground

        val deliveryId = data[ID_KEY]
        val token = data[TOKEN_KEY]
        if (data[VERSION_KEY] != "1" || deliveryId.isNullOrBlank() || token.isNullOrBlank() ||
            data.keys.any(::isReserved) || message.notification != null
        ) {
            return BackgroundPushParseResult.Unsupported
        }

        val businessData = data.filterKeys { key ->
            val lower = key.lowercase()
            key !in metadataKeys && !lower.startsWith("google.") && !lower.startsWith("gcm.")
        }
        return BackgroundPushParseResult.Received(CustomerIOBackgroundPushPayload(deliveryId, businessData, token))
    }

    private fun isReserved(key: String): Boolean {
        val lower = key.lowercase()
        return key.isEmpty() || lower in reservedKeys || (lower.startsWith("cio-") && key !in metadataKeys)
    }
}
