package io.customer.messagingpush.processor

import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload
import io.customer.messagingpush.util.PushTrackingUtil

internal sealed class BackgroundPushParseResult {
    object NotBackground : BackgroundPushParseResult()
    object Unsupported : BackgroundPushParseResult()
    class Received(val payload: CustomerIOBackgroundPushPayload, val deliveryToken: String) : BackgroundPushParseResult()
}

internal object BackgroundPushParser {
    private const val PREFIX = "CIO-Background-"
    private val metadataKeys = setOf("${PREFIX}Version", "${PREFIX}Delivery-ID", "${PREFIX}Delivery-Token")

    fun parse(data: Map<String, String>): BackgroundPushParseResult {
        // The legacy relay owns this pair; background envelopes forbid it.
        if (data[PushTrackingUtil.DELIVERY_ID_KEY] != null && data[PushTrackingUtil.DELIVERY_TOKEN_KEY] != null) {
            return BackgroundPushParseResult.NotBackground
        }
        // Existing customer keys may share the prefix without declaring background intent.
        if (metadataKeys.none { it in data }) return BackgroundPushParseResult.NotBackground

        val deliveryId = data["${PREFIX}Delivery-ID"]
        val token = data["${PREFIX}Delivery-Token"]
        if (data["${PREFIX}Version"] != "1" || deliveryId.isNullOrBlank() || token.isNullOrBlank() ||
            data.keys.any { it.lowercase().startsWith("cio-") && it !in metadataKeys }
        ) {
            return BackgroundPushParseResult.Unsupported
        }

        if (data.keys.any { it.isEmpty() || it.lowercase() in setOf("aps", "cio", "notification", "from", "message_type", "collapse_key") }) {
            return BackgroundPushParseResult.Unsupported
        }

        val businessData = data.filterKeys { key ->
            val lower = key.lowercase()
            key !in metadataKeys && !lower.startsWith("google.") && !lower.startsWith("gcm.")
        }
        return BackgroundPushParseResult.Received(CustomerIOBackgroundPushPayload(deliveryId, businessData, token), token)
    }
}
