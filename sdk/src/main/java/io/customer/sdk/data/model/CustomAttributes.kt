package io.customer.sdk.data.model

import io.customer.base.extenstions.getUnixTimestamp
import io.customer.sdk.data.moshi.adapter.CustomAttributesAdapter
import java.util.*
import kotlin.collections.HashMap

typealias CustomAttributes = Map<String, Any>

/**
 * Convert data types of the Map to data types the Customer.io API can understand.
 * Use this function before sending JSON to the [io.customer.sdk.util.JsonAdapter]. The [io.customer.sdk.util.JsonAdapter] will use Moshi with [CustomAttributesAdapter] to do the JSON parsing.
 *
 * @see TestCustomAttributesDataSet
 */
fun CustomAttributes.verify(): CustomAttributes {
    fun getValidValue(any: Any): Any {
        return when (any) {
            is Date -> any.getUnixTimestamp() // The API expects dates to be in Unix time format.
            is Enum<*> -> any.name // Convert Enum data types to String.
            else -> any
        }
    }

    val validMap = HashMap<String, Any>()
    this.entries.forEach {
        validMap[it.key] = getValidValue(it.value)
    }
    return validMap.toMap()
}
