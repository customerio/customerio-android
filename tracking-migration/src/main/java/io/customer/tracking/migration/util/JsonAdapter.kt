package io.customer.tracking.migration.util

import org.json.JSONArray
import org.json.JSONObject

class JsonAdapter {
    fun fromJsonOrNull(json: String): JSONObject? = runCatching {
        JSONObject(json)
    }.getOrNull()

    fun fromJsonToListOrNull(json: String): JSONArray? = runCatching {
        return JSONArray(json)
    }.getOrNull()
}
