package io.customer.datapipelines.data.model

import io.customer.sdk.data.model.CustomAttributes

/**
 * Builder class for creating [CustomAttributes] objects.
 * This class is useful for associating custom attributes with group events.
 * The class simplifies the process of updating reserved traits like objectTypeId,
 * relationshipAttributes, etc. with the help of dedicated methods.
 * Other custom attributes can be added using the [addTrait] method.
 */
class TraitsBuilder {
    private val traits: MutableMap<String, Any> = mutableMapOf()

    /**
     * Adds custom trait to the group/object in Customer.io Journeys.
     */
    fun addTrait(key: String, value: Any): TraitsBuilder {
        traits[key] = value
        return this
    }

    /**
     * Adds custom trait to the group/object in Customer.io Journeys.
     */
    fun addTrait(pair: Pair<String, Any>): TraitsBuilder {
        traits[pair.first] = pair.second
        return this
    }

    /**
     * Object type IDs are stringified integers for the type of group/object the group belongs to.
     * This is a required trait for objects in Customer.io Journeys. If the value
     * is not included, Customer.io Journeys will assume it's 1 (the first kind of object created).
     */
    fun addObjectTypeId(objectTypeId: Long): TraitsBuilder {
        traits["objectTypeId"] = objectTypeId
        return this
    }

    /**
     * Adds attributes that describe the relationship between a person and an object
     * in Customer.io Journeys.
     */
    fun addRelationshipAttributes(attributes: Map<String, Any>): TraitsBuilder {
        traits["relationshipAttributes"] = attributes
        return this
    }

    /**
     * Builds the [CustomAttributes] object with added traits.
     * This method should be called after adding all the required traits.
     */
    fun build(): CustomAttributes {
        return traits
    }
}
