package io.customer.location.geofence

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Project-owned wrapper around `Json { ignoreUnknownKeys = true }` so the geofence
 * sync pipeline doesn't ship a bare framework type.
 *
 * Two decode flavours for the two call sites:
 * - [decode] (strict) — for API responses, where a parse failure should propagate
 *   as a Result.failure to the caller.
 * - [decodeOrNull] (lenient) — for cached state, where schema drift / corruption
 *   should degrade to "no cached value" instead of crashing the sync path.
 */
internal class GeofenceJsonSerializer {

    private val json = Json { ignoreUnknownKeys = true }

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, raw: String): T =
        json.decodeFromString(serializer, raw)

    fun <T> decodeOrNull(serializer: KSerializer<T>, raw: String): T? = try {
        decode(serializer, raw)
    } catch (_: Throwable) {
        null
    }
}
