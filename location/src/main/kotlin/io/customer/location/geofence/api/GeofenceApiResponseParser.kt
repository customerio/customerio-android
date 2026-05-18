package io.customer.location.geofence.api

import kotlinx.serialization.json.Json

/**
 * Decodes raw geofence API response bodies into [GeofenceApiResponse].
 *
 * Owns the kotlinx.serialization config (e.g. tolerance of unknown keys for forward
 * compatibility with backend additions) so callers don't need to know about it.
 */
internal class GeofenceApiResponseParser {
    private val json: Json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): GeofenceApiResponse =
        json.decodeFromString(GeofenceApiResponse.serializer(), raw)
}
