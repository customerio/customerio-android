package io.customer.messagingpush.livenotification.template

import android.graphics.Color
import androidx.annotation.ColorInt
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

/**
 * Reads the `statusColor` field for [key] and parses it to an `@ColorInt`, or
 * null when absent/blank/unparseable. Accepts the hex forms `Color.parseColor`
 * understands (`#RRGGBB`, `#AARRGGBB`, and the named colors); a leading `#` is
 * added when missing. Callers fall back to branding/default when this is null.
 */
@ColorInt
internal fun JSONObject.optColorInt(key: String): Int? {
    val raw = optStringNonEmpty(key) ?: return null
    val candidate = if (raw.startsWith("#")) raw else "#$raw"
    return try {
        Color.parseColor(candidate)
    } catch (e: IllegalArgumentException) {
        null
    }
}
