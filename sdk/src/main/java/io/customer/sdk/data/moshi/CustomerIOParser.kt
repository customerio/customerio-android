package io.customer.sdk.data.moshi

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.customer.sdk.data.moshi.adapter.SupportedAttributesFactory

internal interface CustomerIOParser {

    fun getAttributesParser(): JsonAdapter<Map<String, Any>>
}

internal class CustomerIOParserImpl(val moshi: Moshi) : CustomerIOParser {

    private val modifiedMoshi by lazy {
        moshi.newBuilder()
            .add(SupportedAttributesFactory())
            .build()
    }

    private val jsonAdapter: JsonAdapter<Map<String, Any>> by lazy {
        modifiedMoshi.adapter(
            Types.newParameterizedType(
                MutableMap::class.java,
                String::class.java,
                Any::class.java
            )
        )
    }

    override fun getAttributesParser(): JsonAdapter<Map<String, Any>> = jsonAdapter
}
