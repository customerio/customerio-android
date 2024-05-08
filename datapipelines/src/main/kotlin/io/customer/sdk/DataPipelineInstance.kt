package io.customer.sdk

import com.segment.analytics.kotlin.core.emptyJsonObject
import com.segment.analytics.kotlin.core.utilities.JsonAnySerializer
import io.customer.sdk.android.CustomerIOInstance
import io.customer.sdk.data.model.CustomAttributes
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Extends [CustomerIOInstance] to provide the instance of CustomerIO SDK with
 * Data Pipelines implementation.
 */
abstract class DataPipelineInstance : CustomerIOInstance {
    /**
     * Custom profile attributes to be added to current profile.
     */
    open lateinit var profileAttributes: CustomAttributes

    inline fun <reified Traits> identify(
        userId: String,
        traits: Traits
    ) {
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    fun identify(userId: String, traits: CustomAttributes) {
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    @JvmOverloads
    fun identify(userId: String, traits: JsonObject = emptyJsonObject) {
        identify(userId, traits, JsonAnySerializer.serializersModule.serializer())
    }

    open fun <Traits> identify(
        userId: String,
        traits: Traits,
        serializationStrategy: SerializationStrategy<Traits>
    ) {
    }

    /**
     * Stop identifying the currently persisted customer. All future calls to the SDK will no longer
     * be associated with the previously identified customer.
     * Note: If you simply want to identify a *new* customer, this function call is optional. Simply
     * call `identify()` again to identify the new customer profile over the existing.
     * If no profile has been identified yet, this function will reset anonymous profile.
     */
    open fun clearIdentify() {}
}
