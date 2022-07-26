package io.customer.sdk.data.model

/**
 * Data class that holds payload for notifications from CIO dashboard
 *
 * @property deepLink url received in notification data
 * @property cioDeliveryId Customer.io message delivery id
 * @property title notification content title text
 * @property body notification content body text
 */
data class CustomerIOParsedPushPayload(
    val deepLink: String?,
    val cioDeliveryId: String,
    val title: String,
    val body: String
)
