package io.customer.sdk.repository

import io.customer.base.extenstions.getUnixTimestamp
import io.customer.sdk.data.moshi.CustomerIOParser
import java.util.*
import kotlin.collections.HashMap

internal interface AttributesRepository {
    fun mapToJson(map: Map<String, Any>): Map<String, Any>
}

internal class MoshiAttributesRepositoryImp(
    private val parser: CustomerIOParser,
) : AttributesRepository {

    override fun mapToJson(map: Map<String, Any>): Map<String, Any> {
        return parser.getAttributesParser().fromJsonValue(verifyMap(map)) ?: emptyMap()
    }

    private fun verifyMap(map: Map<String, Any>): Map<String, Any> {
        val validMap = HashMap<String, Any>()
        map.entries.forEach {
            validMap[it.key] = getValidValue(it.value)
        }
        return validMap.toMap()
    }

    // Unix timestamp date are only acceptable
    private fun getValidValue(any: Any): Any {
        return when (any) {
            is Date -> any.getUnixTimestamp()
            is Enum<*> -> any.name
            else -> any
        }
    }
}
