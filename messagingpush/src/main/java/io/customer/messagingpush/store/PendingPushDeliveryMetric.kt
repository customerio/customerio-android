package io.customer.messagingpush.store

import io.customer.sdk.data.store.PendingDeliveryStore
import org.json.JSONObject

/**
 * Represents a push-delivered metric that has been observed locally but not
 * yet confirmed as tracked by the Customer.io backend.
 *
 * Entries are keyed by [deliveryId] (the natural identifier carried on the
 * push payload) and stamped with the [timestamp] captured at append time.
 * Entries are removed only when the primary delivery path (WorkManager job
 * or its direct-HTTP fallback) reports a successful response, or when the
 * foreground handoff hands them off to the analytics pipeline.
 */
internal data class PendingPushDeliveryMetric(
    val deliveryId: String,
    val token: String,
    val timestamp: Long
) {
    internal object Serializer : PendingDeliveryStore.Serializer<PendingPushDeliveryMetric> {
        private const val KEY_DELIVERY_ID = "deliveryId"
        private const val KEY_TOKEN = "token"
        private const val KEY_TIMESTAMP = "timestamp"

        override fun key(entry: PendingPushDeliveryMetric): String = entry.deliveryId

        override fun timestamp(entry: PendingPushDeliveryMetric): Long = entry.timestamp

        override fun toJson(entry: PendingPushDeliveryMetric): JSONObject = JSONObject().apply {
            put(KEY_DELIVERY_ID, entry.deliveryId)
            put(KEY_TOKEN, entry.token)
            put(KEY_TIMESTAMP, entry.timestamp)
        }

        override fun fromJson(obj: JSONObject): PendingPushDeliveryMetric? {
            val deliveryId = obj.optString(KEY_DELIVERY_ID).takeIf { it.isNotBlank() } ?: return null
            val token = obj.optString(KEY_TOKEN).takeIf { it.isNotBlank() } ?: return null
            if (!obj.has(KEY_TIMESTAMP)) return null
            val timestamp = obj.optLong(KEY_TIMESTAMP, Long.MIN_VALUE)
            if (timestamp == Long.MIN_VALUE) return null
            return PendingPushDeliveryMetric(
                deliveryId = deliveryId,
                token = token,
                timestamp = timestamp
            )
        }
    }

    companion object {
        internal const val FILE_NAME = "cio_pending_push_delivery.json"
    }
}
