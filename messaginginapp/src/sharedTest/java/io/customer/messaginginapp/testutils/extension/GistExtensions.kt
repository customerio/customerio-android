package io.customer.messaginginapp.testutils.extension

import build.gist.data.model.Message
import io.customer.messaginginapp.type.InAppMessage
import io.customer.sdk.extensions.random

fun Message.getNewFromInApp(
    message: InAppMessage
): Message = Message(
    instanceId = message.instanceId,
    messageId = message.messageId,
    properties = mapOf(
        Pair(
            "gist",
            mapOf(
                Pair("campaignId", message.deliveryId)
            )
        )
    )
)

fun Message.getNewRandom(): Message = getNewFromInApp(InAppMessage(String.random, String.random, String.random))
