package io.customer.sdk.data.moshi.adapter

import com.squareup.moshi.*
import io.customer.base.extenstions.getUnixTimestamp
import io.customer.sdk.data.model.CustomAttributes
import java.lang.reflect.Type
import java.math.BigDecimal
import java.util.*

internal class CustomAttributesFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        if (Types.getRawType(type) != Map::class.java) {
            return null
        }
        return CustomAttributesAdapter(moshi).nullSafe()
    }
}

/**
 * Moshi [JsonAdapter] for supporting [CustomAttributes] for the SDK.
 *
 * Duties of the JsonAdapter to support:
 * * For numbers inside of JSON, parse them to [BigDecimal]. Moshi by default uses [Double] which may not be big enough for a customer's use.
 * * Convert [Date] to [Long] (unix timestamp) as that's what the Customer.IO API expects.
 * * Convert [Enum] to [String] data type.
 * * Because the Map allows Any, filter out values that Moshi does not support. This lessons the chance of sending attributes to the API that the API cannot understand. This filtering is done *before* JSON parsing. Therefore, use the included [CustomAttributes.verify] extensions before using the [io.customer.sdk.util.JsonAdapter].
 */
internal class CustomAttributesAdapter(moshi: Moshi) :
    JsonAdapter<CustomAttributes>() {

    private val elementAdapter: JsonAdapter<Any> = moshi.adapter(Any::class.java)
    private val elementBigDecimalAdapter: JsonAdapter<BigDecimal> =
        moshi.adapter(BigDecimal::class.java)

    override fun fromJson(reader: JsonReader): CustomAttributes {
        val result = mutableMapOf<String, Any>()
        reader.beginObject()
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            try {
                val name = reader.nextName()
                val peeked = reader.peekJson()
                if (peeked.peek() == JsonReader.Token.NUMBER) {
                    result[name] = elementBigDecimalAdapter.fromJson(peeked)!!
                } else {
                    result[name] = elementAdapter.fromJson(peeked)!!
                }
            } catch (ignored: JsonDataException) {
            }
            reader.skipValue()
        }
        reader.endObject()
        return result
    }

    override fun toJson(writer: JsonWriter, value: CustomAttributes?) {
        if (value == null) {
            throw NullPointerException("value was null! Wrap in .nullSafe() to write nullable values.")
        }
        val value = verifyCustomAttributes(value)

        writer.beginObject()

        value.forEach {
            try {
                /**
                 * If moshi can't serialize an object, it will throw an exception and crash the SDK.
                 * To avoid the SDK crashing, try to see if Moshi is able to serialize the object.
                 * If it is able to, then let's use the JSON writer to write the object to the JSON string. Else, ignore the attribute.
                 */
                elementAdapter.toJson(it.value) // our test. will throw exception if can't serialize.

                // Write to json string since we are confident that it's able to be serialized now.
                writer.name(it.key)
                elementAdapter.toJson(writer, it.value)
            } catch (e: Throwable) {
                // Ignore element if it can't be serialized.
                // Have automated tests written against SDK objects to assert the SDK models are able to
                // serialize.
            }
        }

        writer.endObject()
    }

    /**
     * Convert data types of the Map to data types the Customer.io API can understand. We want to run this function before Moshi converts our Map into a JSON string that then gets sent to theAPI.
     */
    private fun verifyCustomAttributes(value: CustomAttributes): CustomAttributes {
        fun getValidValue(any: Any): Any {
            return when (any) {
                is Date -> any.getUnixTimestamp() // The API expects dates to be in Unix time format.
                is Enum<*> -> any.name // Convert Enum data types to String.
                else -> any
            }
        }

        val validMap = mutableMapOf<String, Any>()
        value.entries.forEach {
            validMap[it.key] = getValidValue(it.value)
        }
        return validMap.toMap()
    }
}
