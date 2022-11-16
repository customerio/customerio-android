package io.customer.messaginginapp.type

import build.gist.data.model.GistMessageProperties
import build.gist.data.model.Message

data class InAppMessage(
    val instanceId: String,
    val messageId: String,
    val deliveryId: String? // (Currently taken from Gist's campaignId property). Can be null when sending test in-app messages
) {
    companion object {
        internal fun getFromGistMessage(gistMessage: Message): InAppMessage {
            val gistProperties = GistMessageProperties.getGistProperties(gistMessage)
            val campaignId = gistProperties.campaignId

            return InAppMessage(
                instanceId = gistMessage.instanceId,
                messageId = gistMessage.messageId,
                deliveryId = campaignId
            )
        }
    }
}
