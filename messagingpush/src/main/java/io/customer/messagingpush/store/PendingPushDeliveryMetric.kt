package io.customer.messagingpush.store

import io.customer.sdk.data.store.PendingDeliveryStore
import kotlinx.serialization.Serializable

/**
 * Represents a push-delivered metric that has been observed locally but not
 * yet confirmed as tracked by the Customer.io backend.
 *
 * Entries are keyed by [deliveryId] — the natural identifier carried on the
 * push payload. They are removed only when the primary delivery path
 * (WorkManager job or its direct-HTTP fallback) reports a successful
 * response, or when the foreground handoff hands them off to the analytics
 * pipeline.
 *
 * The shared [PendingDeliveryStore] requires entries to expose a generic
 * `key`. We expose [deliveryId] under both its domain name and as `key` so
 * handoff code can use the generic contract without losing push-specific
 * readability at the call site.
 */
@Serializable
internal data class PendingPushDeliveryMetric(
    val deliveryId: String,
    val token: String
) : PendingDeliveryStore.PendingDeliveryEntry {
    override val key: String get() = deliveryId

    companion object {
        internal const val FILE_NAME = "cio_pending_push_delivery.json"
    }
}
