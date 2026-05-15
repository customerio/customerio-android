package io.customer.messagingpush.store

/**
 * Represents a push-delivered metric that has been observed locally but not yet
 * confirmed as tracked by the Customer.io backend.
 *
 * Entries are persisted by [PendingPushDeliveryStore] and removed only when the
 * primary delivery path (WorkManager job or its direct-HTTP fallback) reports a
 * successful response, or when the app-launch flush hands them off to the
 * analytics pipeline.
 */
internal data class PendingPushDeliveryMetric(
    val id: String,
    val deliveryId: String,
    val token: String
)
