package io.customer.messagingpush.processor

import com.google.firebase.messaging.RemoteMessage
import io.customer.messagingpush.data.model.CustomerIOBackgroundPushPayload
import io.customer.messagingpush.util.PushTrackingUtil

internal sealed class BackgroundPushParseResult {
    /** Not a silent push, so the regular push handling applies. */
    object NotBackground : BackgroundPushParseResult()

    /** Has the silent delivery ID but a blank ID or no token. It is dropped without display. */
    object Unsupported : BackgroundPushParseResult()

    class Received(val payload: CustomerIOBackgroundPushPayload) : BackgroundPushParseResult()
}

internal object BackgroundPushParser {
    private const val ID_KEY = "CIO-Background-Delivery-ID"
    private const val TOKEN_KEY = "CIO-Background-Delivery-Token"

    fun parse(message: RemoteMessage): BackgroundPushParseResult {
        val data = message.data
        // A complete legacy pair always gets the regular push handling.
        if (data[PushTrackingUtil.DELIVERY_ID_KEY] != null && data[PushTrackingUtil.DELIVERY_TOKEN_KEY] != null) {
            return BackgroundPushParseResult.NotBackground
        }
        val deliveryId = data[ID_KEY] ?: return BackgroundPushParseResult.NotBackground
        val token = data[TOKEN_KEY]
        if (deliveryId.isBlank() || token.isNullOrBlank()) return BackgroundPushParseResult.Unsupported

        return BackgroundPushParseResult.Received(CustomerIOBackgroundPushPayload(deliveryId, data - ID_KEY - TOKEN_KEY, token))
    }
}
