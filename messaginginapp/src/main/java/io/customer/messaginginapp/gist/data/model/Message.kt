package io.customer.messaginginapp.gist.data.model

import io.customer.sdk.core.di.SDKComponent
import java.util.UUID

enum class MessagePosition(val position: String) {
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom")
}

data class BroadcastFrequency(
    val count: Int,
    val delay: Int,
    val ignoreDismiss: Boolean = false
)

data class BroadcastProperties(
    val frequency: BroadcastFrequency
)

data class GistProperties(
    val routeRule: String?,
    val elementId: String?,
    val campaignId: String?,
    val position: MessagePosition,
    val persistent: Boolean,
    // This color is formated as #RRGGBBAA
    val overlayColor: String?,
    val broadcast: BroadcastProperties?
)

data class Message(
    val messageId: String = "",
    val priority: Int? = null,
    val queueId: String? = null,
    val properties: Map<String, Any?>? = null
) {
    // Should be property and not constructor parameter so it isn't used in equals
    // As messages are identified uniquely by their queueId and not instanceId
    val instanceId: String = UUID.randomUUID().toString()
    val gistProperties: GistProperties
        get() = convertToGistProperties()

    val embeddedElementId: String?
        get() = gistProperties.elementId

    val isEmbedded: Boolean
        get() = embeddedElementId != null

    private fun convertToGistProperties(): GistProperties {
        var routeRule: String? = null
        var elementId: String? = null
        var campaignId: String? = null
        var position: MessagePosition = MessagePosition.CENTER
        var persistent = false
        var overlayColor: String? = null
        var broadcast: BroadcastProperties? = null

        (properties?.get("gist") as? Map<String, Any?>)?.let { gistProperties ->
            gistProperties["routeRuleAndroid"]?.let { rule ->
                (rule as String).let { stringRule ->
                    routeRule = stringRule
                }
            }
            gistProperties["campaignId"]?.let { id ->
                (id as String).let { stringId ->
                    campaignId = stringId
                }
            }
            gistProperties["elementId"]?.let { id ->
                (id as String).let { stringId ->
                    elementId = stringId
                }
            }
            gistProperties["position"]?.let { messagePosition ->
                (messagePosition as String).let { stringPosition ->
                    position = MessagePosition.valueOf(stringPosition.uppercase())
                }
            }
            gistProperties["persistent"]?.let { id ->
                (id as? Boolean)?.let { persistentValue ->
                    persistent = persistentValue
                }
            }
            gistProperties["overlayColor"]?.let { id ->
                (id as? String)?.let { color ->
                    overlayColor = color
                }
            }
            gistProperties["broadcast"]?.let { broadcastData ->
                (broadcastData as? Map<String, Any?>)?.let { broadcastMap ->
                    broadcastMap["frequency"]?.let { frequencyData ->
                        (frequencyData as? Map<String, Any?>)?.let { frequencyMap ->
                            val count = (frequencyMap["count"] as? Number)?.toInt()
                            val delay = (frequencyMap["delay"] as? Number)?.toInt()
                            val ignoreDismiss = (frequencyMap["ignoreDismiss"] as? Boolean) ?: false

                            // Validate required fields - match web SDK behavior but with logging
                            if (count == null || delay == null) {
                                SDKComponent.logger.error("Anonymous message has invalid frequency data - count: $count, delay: $delay. Message will be skipped.")
                                return@let
                            }

                            broadcast = BroadcastProperties(
                                frequency = BroadcastFrequency(
                                    count = count,
                                    delay = delay,
                                    ignoreDismiss = ignoreDismiss
                                )
                            )
                        }
                    }
                }
            }
        }
        return GistProperties(
            routeRule = routeRule,
            elementId = elementId,
            campaignId = campaignId,
            position = position,
            persistent = persistent,
            overlayColor = overlayColor,
            broadcast = broadcast
        )
    }

    override fun toString(): String {
        return "Message(messageId=$messageId, instanceId=$instanceId, priority=$priority, queueId=$queueId, properties=$gistProperties"
    }
}

/**
 * Extension function to check if a message matches the current route
 */
internal fun Message.matchesRoute(currentRoute: String?): Boolean {
    val routeRule = this.gistProperties.routeRule
    return when {
        routeRule == null -> true
        currentRoute == null -> false
        else -> {
            val result = runCatching {
                routeRule.toRegex().matches(currentRoute)
            }

            if (result.isFailure) {
                // Log the error just like in Swift
                SDKComponent.logger.debug("Problem processing route rule message regex: $routeRule")
                false
            } else {
                result.getOrDefault(false)
            }
        }
    }
}

/**
 * Extension function to check if a message is an anonymous message
 */
fun Message.isMessageAnonymous(): Boolean {
    return this.gistProperties.broadcast != null
}

/**
 * Extension function to check if an anonymous message should show always
 */
fun Message.isShowAlwaysAnonymous(): Boolean {
    if (!isMessageAnonymous()) return false
    val anonymousDetails = this.gistProperties.broadcast!!.frequency
    return anonymousDetails.delay == 0 && anonymousDetails.count == 0
}
