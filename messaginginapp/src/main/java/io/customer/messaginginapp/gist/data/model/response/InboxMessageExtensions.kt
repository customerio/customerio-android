package io.customer.messaginginapp.gist.data.model.response

import io.customer.messaginginapp.gist.data.model.InboxMessage

// Formats inbox message for logging.
internal fun InboxMessage.toLogString(): String = "$queueId (deliveryId: $deliveryId)"
