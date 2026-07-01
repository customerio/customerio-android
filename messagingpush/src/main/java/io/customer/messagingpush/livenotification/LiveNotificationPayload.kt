package io.customer.messagingpush.livenotification

import org.json.JSONArray
import org.json.JSONObject

/**
 * Coerces live-notification template fields into values the data pipeline can
 * serialize: `org.json` containers (e.g. FlightStatus' `origin`) become plain
 * [Map]/[List], and null entries are dropped. Used by the on-device start/update
 * report path ([LiveNotificationManager]).
 */
internal fun Map<String, Any?>.toJsonSafePayload(): Map<String, Any?> =
    buildMap {
        for ((key, value) in this@toJsonSafePayload) {
            if (value != null) put(key, value.toJsonSafe())
        }
    }

private fun Any?.toJsonSafe(): Any? = when (this) {
    null -> null
    is JSONObject -> buildMap<String, Any?> {
        for (key in keys()) put(key, opt(key)?.takeIf { it != JSONObject.NULL }?.toJsonSafe())
    }
    is JSONArray -> (0 until length()).map { opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toJsonSafe() }
    else -> this
}
