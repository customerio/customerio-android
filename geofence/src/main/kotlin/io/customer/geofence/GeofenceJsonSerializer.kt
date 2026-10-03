package io.customer.geofence

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * `lenient` is for API responses (e.g. an unquoted `"id": 123` for a String). Cache reads stay
 * strict: we wrote that JSON, so leniency would only mask corruption.
 */
internal class GeofenceJsonSerializer {

    private val strictJson = Json { ignoreUnknownKeys = true }
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        strictJson.encodeToString(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, raw: String, lenient: Boolean = false): T =
        (if (lenient) lenientJson else strictJson).decodeFromString(serializer, raw)

    fun <T> decodeOrNull(
        serializer: KSerializer<T>,
        raw: String,
        lenient: Boolean = false
    ): T? = try {
        decode(serializer, raw, lenient)
    } catch (e: CancellationException) {
        // Rethrow so a suspending caller still gets cancelled.
        throw e
    } catch (_: Exception) {
        null
    }
}
