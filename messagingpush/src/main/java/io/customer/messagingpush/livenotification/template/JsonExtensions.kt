package io.customer.messagingpush.livenotification.template

import org.json.JSONObject

/**
 * Returns the string value for [key], or null when the key is absent, holds an
 * explicit JSON null, or is empty.
 *
 * Prefer this over `optString(key).takeIf { it.isNotEmpty() }`: `optString`
 * returns the literal string `"null"` for an explicit JSON null, which would
 * otherwise slip past an `isNotEmpty()` guard and render as visible text.
 */
internal fun JSONObject.optStringNonEmpty(key: String): String? {
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotEmpty() }
}
