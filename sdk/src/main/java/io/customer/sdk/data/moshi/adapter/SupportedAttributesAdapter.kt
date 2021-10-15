package io.customer.sdk.data.moshi.adapter

import com.squareup.moshi.*
import java.lang.reflect.Type

internal class SupportedAttributesFactory : JsonAdapter.Factory {
    override fun create(
        type: Type,
        annotations: MutableSet<out Annotation>,
        moshi: Moshi
    ): JsonAdapter<*>? {
        if (Types.getRawType(type) != Map::class.java) {
            return null
        }
        return SupportedAttributesAdapter(moshi).nullSafe()
    }
}

internal class SupportedAttributesAdapter(moshi: Moshi) :
    JsonAdapter<Map<String, Any>>() {

    private val elementAdapter: JsonAdapter<Any> = moshi.adapter(Any::class.java)

    private val mapAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Any::class.java
            )
        )

    override fun fromJson(reader: JsonReader): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        reader.beginObject()
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            try {
                val name = reader.nextName()
                val peeked = reader.peekJson()
                result[name] = elementAdapter.fromJson(peeked)!!
            } catch (ignored: JsonDataException) {
            }
            reader.skipValue()
        }
        reader.endObject()
        return result
    }

    override fun toJson(writer: JsonWriter, value: Map<String, Any>?) {
        if (value == null) {
            throw NullPointerException("value was null! Wrap in .nullSafe() to write nullable values.")
        }
        writer.beginObject()
        mapAdapter.toJson(writer, value)
        writer.endObject()
    }
}
