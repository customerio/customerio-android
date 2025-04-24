package io.customer.messaginginapp.gist.data.model

import io.customer.sdk.core.di.SDKComponent
import java.util.UUID

enum class MessagePosition(val position: String) {
    TOP("top"),
    CENTER("center"),
    BOTTOM("bottom")
}

data class GistProperties(
    val routeRule: String?,
    val elementId: String?,
    val campaignId: String?,
    val position: MessagePosition,
    val persistent: Boolean,
    // This color is formated as #RRGGBBAA
    val overlayColor: String?
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
        }
        return GistProperties(
            routeRule = routeRule,
            elementId = elementId,
            campaignId = campaignId,
            position = position,
            persistent = persistent,
            overlayColor = overlayColor
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
