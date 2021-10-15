package io.customer.sdk.repository

import com.squareup.moshi.JsonAdapter
import java.util.*
import kotlin.collections.HashMap

internal interface AttributesRepository {
    fun mapToJson(map: Map<String, Any>): Map<String, Any>
}

internal class MoshiAttributesRepositoryImp(
    private val jsonAdapter: JsonAdapter<Map<String, Any>>,
) : AttributesRepository {

    override fun mapToJson(map: Map<String, Any>): Map<String, Any> {
        return jsonAdapter.fromJsonValue(verifyMap(map)) ?: emptyMap()
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
            is Date -> any.time
            else -> any
        }
    }
}
