package io.customer.messaginginapp.type

import build.gist.data.model.GistMessageProperties
import build.gist.data.model.Message

data class InAppMessage(
    val instanceId: String,
    val messageId: String,
    val deliveryId: String // (Currently taken from Gist's campaignId property)
) {
    companion object {
        internal fun getFromGistMessage(gistMessage: Message): InAppMessage {
            // From viewing the Gist SDK source code, looks like the campaign id of a message is always populated.
            // Here is where Messages are created: https://gitlab.com/bourbonltd/gist-android/-/blob/467deb23be88fda089d0f3d85e23b3b1ba0f91fe/gist/src/main/java/build/gist/presentation/GistView.kt#L86
            val gistProperties = GistMessageProperties.getGistProperties(gistMessage)
            val campaignId = gistProperties.campaignId!!

            return InAppMessage(
                instanceId = gistMessage.instanceId,
                messageId = gistMessage.messageId,
                deliveryId = campaignId
            )
        }
    }
}
