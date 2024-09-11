package io.customer.messaginginapp.type

import io.customer.messaginginapp.gist.data.model.Message

data class InAppMessage(
    val messageId: String,
    val queueId: String?,
    val deliveryId: String? // (Currently taken from Gist's campaignId property). Can be null when sending test in-app messages
) {
    companion object {
        internal fun getFromGistMessage(gistMessage: Message): InAppMessage {
            val gistProperties = gistMessage.gistProperties
            val campaignId = gistProperties.campaignId

            return InAppMessage(
                messageId = gistMessage.messageId,
                deliveryId = campaignId,
                queueId = gistMessage.queueId
            )
        }
    }
}

fun InAppMessage.getMessage(): Message = Message(
    messageId = this.messageId,
    queueId = this.queueId,
    properties = mapOf(
        Pair(
            "gist",
            mapOf(
                Pair("campaignId", this.deliveryId)
            )
        )
    )
)
