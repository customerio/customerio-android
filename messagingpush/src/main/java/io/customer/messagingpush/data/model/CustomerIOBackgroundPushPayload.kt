package io.customer.messagingpush.data.model

/** Customer business data, excluding Customer.io receipt and provider metadata. */
class CustomerIOBackgroundPushPayload internal constructor(
    val deliveryId: String,
    val data: Map<String, String>,
    /** The original target token, for manual delivery tracking when automatic metrics are disabled. */
    val deliveryToken: String
)
