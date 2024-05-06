package io.customer.sdk

import io.customer.sdk.android.CustomerIOInstance
import io.customer.sdk.data.model.CustomAttributes
import kotlinx.serialization.SerializationStrategy

/**
 * Extends [CustomerIOInstance] to provide the instance of CustomerIO SDK with
 * Data Pipelines implementation.
 */
interface DataPipelineInstance : CustomerIOInstance {
    var profileAttributes: CustomAttributes
    fun identify(userId: String)
    fun identify(userId: String, traits: CustomAttributes)
    fun <Body> identify(
        userId: String,
        traits: Body,
        serializationStrategy: SerializationStrategy<Body>
    )

    fun clearIdentify()
}
