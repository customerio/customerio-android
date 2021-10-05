package io.customer.sdk.data.model


sealed class IdentityAttributeValue {
    class StringAttribute(val value: String) : IdentityAttributeValue()
    class IntAttribute(val value: Int) : IdentityAttributeValue()
    class BoolAttribute(val value: Boolean) : IdentityAttributeValue()

    fun getValue(): Any {
        return when (this) {
            is BoolAttribute -> value
            is IntAttribute -> value
            is StringAttribute -> value
        }
    }
}

typealias IdentityAttributeMap = Map<String, IdentityAttributeValue>
