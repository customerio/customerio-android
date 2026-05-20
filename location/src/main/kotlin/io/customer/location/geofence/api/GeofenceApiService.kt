package io.customer.location.geofence.api

import io.customer.location.geofence.GeofenceJsonSerializer
import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpMethod
import io.customer.sdk.core.network.HttpRequestParams

internal interface GeofenceApiService {
    suspend fun fetchGeofences(
        latitude: Double,
        longitude: Double
    ): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient,
    private val jsonSerializer: GeofenceJsonSerializer
) : GeofenceApiService {

    override suspend fun fetchGeofences(
        latitude: Double,
        longitude: Double
    ): Result<GeofenceApiResponse> {
        // ⚠️ TESTING-ONLY short-circuit. See location/MOCK_TESTING.md.
        // Set USE_MOCK_RESPONSE to false on this branch to hit the real backend
        // instead. Decoded through the same lenient path as the real wire.
        if (USE_MOCK_RESPONSE) {
            return runCatching {
                jsonSerializer.decode(
                    GeofenceApiResponse.serializer(),
                    MOCK_RESPONSE_JSON,
                    lenient = true
                )
            }
        }

        val params = HttpRequestParams(
            path = ENDPOINT_PATH,
            method = HttpMethod.GET,
            queryParams = mapOf(
                "latitude" to latitude.toString(),
                "longitude" to longitude.toString()
            )
        )

        return httpClient.request(params).mapCatching { responseBody ->
            // Lenient at the wire boundary so the SDK doesn't pin a specific
            // type for `id` — accepts either numeric or quoted-string form.
            jsonSerializer.decode(GeofenceApiResponse.serializer(), responseBody, lenient = true)
        }
    }

    private companion object {
        private const val ENDPOINT_PATH = "/geofences/nearby"

        // ====================================================================
        // TESTING-ONLY (geofence-testing branch). See location/MOCK_TESTING.md.
        // Flip USE_MOCK_RESPONSE to false to hit the real backend instead.
        // Edit location/mock/regions.json and run location/mock/generate.py
        // to regenerate MOCK_RESPONSE_JSON below.
        // ====================================================================
        private const val USE_MOCK_RESPONSE = true

        // === BEGIN GENERATED MOCK ===
        private val MOCK_RESPONSE_JSON = """
              {
                "config": {
                  "local_refresh_trigger_radius": 1000,
                  "remote_fetch_refresh_trigger_radius": 5000,
                  "remote_fetch_refresh_expiry_time": 86400000,
                  "duplicate_events_expiry_time": 3600000,
                  "android": {
                    "max_business_geofence": 19
                  }
                },
                "geofences": [
                  {
                    "id": "1-ferry-building",
                    "name": "Ferry Building",
                    "latitude": 37.7955,
                    "longitude": -122.3937,
                    "radius": 150,
                    "external_id": "test-1"
                  },
                  {
                    "id": "2-office",
                    "name": "Office",
                    "latitude": 37.422,
                    "longitude": -122.0841,
                    "radius": 150,
                    "external_id": "test-2"
                  }
                ]
              }
        """.trimIndent()
        // === END GENERATED MOCK ===
    }
}
