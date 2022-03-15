package io.customer.sdk.data.moshi.adapter

import com.squareup.moshi.*
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

    private val mapAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Any::class.java
            )
        )

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
        writer.beginObject()
        mapAdapter.toJson(writer, value)
        writer.endObject()
    }
}
