package io.customer.messaginginapp.testutils.extension

import io.customer.messaginginapp.gist.data.model.Message

/**
 * Creates a mock Message object with specified properties for testing.
 */
internal fun createInAppMessage(
    messageId: String = java.util.UUID.randomUUID().toString(),
    elementId: String? = null,
    routeRule: String? = null,
    persistent: Boolean = false,
    priority: Int = 1
): Message {
    val properties = mapOf(
        "gist" to mapOf(
            "elementId" to elementId,
            "routeRuleAndroid" to routeRule,
            "position" to "CENTER",
            "persistent" to persistent
        )
    )

    return Message(
        messageId = messageId,
        properties = properties,
        priority = priority
    )
}

/**
 * Extension function for tests to check if a message matches a route.
 */
internal fun Message.testMatchesRoute(currentRoute: String?): Boolean {
    val routeRule = this.gistProperties.routeRule
    return when {
        routeRule == null -> true
        currentRoute == null -> false
        else -> {
            try {
                routeRule.toRegex().matches(currentRoute)
            } catch (e: Exception) {
                false
            }
        }
    }
}
