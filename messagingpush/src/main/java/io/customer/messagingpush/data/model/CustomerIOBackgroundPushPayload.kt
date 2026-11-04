package io.customer.messagingpush.data.model

/**
 * A silent push received by
 * [io.customer.messagingpush.data.communication.CustomerIOBackgroundPushCallback].
 */
class CustomerIOBackgroundPushPayload internal constructor(
    /** Identifies this delivery. A repeated push carries the same ID, so key your work on it. */
    val deliveryId: String,
    /** The data set on the message, without Customer.io delivery keys or Firebase metadata. */
    val data: Map<String, String>,
    /** The original target token, for manual delivery tracking when automatic metrics are disabled. */
    val deliveryToken: String
)
