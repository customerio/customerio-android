package io.customer.tracking.migration.extensions

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.toList(): List<JSONObject> {
    return (0 until length()).mapNotNull { index -> optJSONObject(index) }
}

internal fun JSONObject.stringOrNull(key: String): String? {
    return if (isNull(key)) null else optString(key)
}
