package io.customer.geofence

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Project-owned JSON wrapper for the geofence sync pipeline.
 *
 * Two decode entry points for the two failure modes:
 * - [decode] — surface parse failures (API responses; failure → `Result.failure`).
 * - [decodeOrNull] — swallow parse failures (cached state; failure → `null` and the
 *   key gets wiped by the caller).
 *
 * Opt-in `lenient` flag accepts loose wire types (e.g. `"id": 123` or `"id": "abc-123"`)
 * without committing the SDK to a specific shape. Cache reads stay strict — we wrote
 * that JSON ourselves, so loose parsing would only mask corruption.
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
        // Always propagate coroutine cancellation; otherwise callers from a
        // suspending context could miss being cancelled.
        throw e
    } catch (_: Exception) {
        null
    }
}
