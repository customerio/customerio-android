package io.customer.location.geofence.api

import io.customer.sdk.core.network.CustomerIOHttpClient
import io.customer.sdk.core.network.HttpRequestParams
import org.json.JSONObject

internal interface GeofenceApiService {
    suspend fun fetchGeofences(
        userId: String,
        latitude: Double,
        longitude: Double
    ): Result<GeofenceApiResponse>
}

internal class GeofenceApiServiceImpl(
    private val httpClient: CustomerIOHttpClient
) : GeofenceApiService {

    override suspend fun fetchGeofences(
        userId: String,
        latitude: Double,
        longitude: Double
    ): Result<GeofenceApiResponse> {
        // TODO(MBL-1623): delete this guard once ENDPOINT_PATH below is set.
        if (ENDPOINT_PATH.isBlank()) {
            return Result.failure(
                IllegalStateException("Geofence API endpoint not yet available (MBL-1623)")
            )
        }

        val body = JSONObject().apply {
            put("userId", userId)
            put("latitude", latitude)
            put("longitude", longitude)
        }.toString()

        val params = HttpRequestParams(
            path = ENDPOINT_PATH,
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = body
        )

        return httpClient.request(params).mapCatching { responseBody ->
            GeofenceApiJson.decodeFromString(GeofenceApiResponse.serializer(), responseBody)
        }
    }

    private companion object {
        // TODO(MBL-1623): Backend endpoint for nearby-geofence fetch is not yet
        //  defined. Empty path => the service short-circuits to Result.failure,
        //  so no stray network calls to a placeholder URL. Replace with the
        //  real path once backend exposes it; the request/decode pipeline above
        //  will start serving live data with no other changes. When wiring
        //  this, also add service tests: success decode, request body shape,
        //  HTTP failure propagation, malformed-response failure.
        const val ENDPOINT_PATH = ""
    }
}
