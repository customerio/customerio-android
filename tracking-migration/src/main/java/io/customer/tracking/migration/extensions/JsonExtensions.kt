package io.customer.tracking.migration.extensions

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.toList(): List<JSONObject> {
    return (0 until length()).mapNotNull(::optJSONObject)
}

internal fun JSONObject.jsonObjectOrNull(key: String): JSONObject? {
    return if (isNull(key)) null else optJSONObject(key)
}

internal fun JSONObject.stringOrNull(key: String): String? {
    return if (isNull(key)) null else optString(key)
}

internal fun JSONObject.longOrNull(key: String): Long? {
    return if (isNull(key)) null else optLong(key)
}
